package com.springai.springai.text2sql.strategy;

import com.springai.springai.demo.service.TableSchemaService;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 方案⑨：源码 WHERE 过滤字段先验（Where-Hint）—— 把"各表在 IMS 真实代码里常用的过滤字段"注入 Prompt。
 *
 * 【痛点（用户实测）】
 *   1) 列选错：问"设备名='A-A-2005'"却生成 device_code='A-A-2005'（应为 device_name）。
 *   2) 时间相对化：问"8月10号的数据"却生成 - INTERVAL '7 day' 相对时间，而非 start_time/end_time 区间。
 *
 * 【本方案做法】
 *   由 extract_where_hints.py 从 200+ 个 MyBatis Mapper XML 确定性抽取"每张表常用的 WHERE 字段 + 运算 + 频次"，
 *   存入 vector_store(type=where_hint)。提问时按 schema 召回出的表名精确拉取本策略，拼成 Prompt 段。
 *
 * 【与 glossary / EXPLAIN 的分工（三层互补）】
 *   - glossary：业务语义消歧（"设备名"→device_name），权威但靠人工/抽取维护。
 *   - where_hint：统计先验（device 表 13 次用 device_name、1 次用 device_code），来自源码、零编造、全量覆盖。
 *   - EXPLAIN：结构校验兜底（列是否存在），不校验语义。
 *   三者都是"hint/兜底"，没有一个是硬规则——where_hint 明确标注"仅供参考，语义明确时以语义为准"。
 *
 * 【定位】这是 glossary + 校验的"补充信号"，单用不能根治列选错（频次≠语义），
 * 但能显著把 LLM 的先验拉向源码真实写法，直接缓解上述两类问题。
 */
@Component
public class WhereHintStrategy {

    /** 单问题最多注入几张表的 where_hint，避免 Prompt 过长 */
    private static final int MAX_TABLES = 8;

    private final TableSchemaService tableSchemaService;

    public WhereHintStrategy(TableSchemaService tableSchemaService) {
        this.tableSchemaService = tableSchemaService;
    }

    /**
     * @param question 用户问题（保留以备将来扩展为混合召回）
     * @param tables   schema 召回出的相关表名（按表精确拉取，比问题向量检索更准）
     * @param enabled  是否启用（开关便于 A/B 对照）
     * @return 注入文本段 + 命中表明细
     */
    public WhereHintResult build(String question, List<String> tables, boolean enabled) {
        if (!enabled || tables == null || tables.isEmpty()) {
            return new WhereHintResult("", List.of());
        }

        List<Document> docs = tableSchemaService.findWhereHintsByTables(tables);
        if (docs.isEmpty()) {
            return new WhereHintResult("", List.of());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【各表常用过滤字段（来自 IMS 源码真实查询模式，仅供参考，非硬性限制）】\n");

        List<Map<String, Object>> matched = new ArrayList<>();
        int n = 0;
        for (Document doc : docs) {
            if (n++ >= MAX_TABLES) break;
            sb.append(doc.getText()).append("\n\n");
            Map<String, Object> m = new HashMap<>();
            m.put("table", strOf(doc.getMetadata().get("table")));
            matched.add(m);
        }
        return new WhereHintResult(sb.toString().strip(), matched);
    }

    private static String strOf(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    }
