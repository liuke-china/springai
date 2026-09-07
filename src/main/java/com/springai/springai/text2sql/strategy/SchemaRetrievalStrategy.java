package com.springai.springai.text2sql.strategy;

import com.springai.springai.smalldemo.service.TableSchemaService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Schema RAG（表结构检索增强）—— 企业 NL2SQL 性价比第一环。
 *
 * 【做什么】
 *   用户问题 → Embedding 向量检索最相关的 N 张表 → 只把相关表的 DDL 注入 Prompt。
 *
 * 【不做什么】
 *   不做术语注入（GlossaryStrategy）、不做关联注入（KnowledgeAugmentStrategy）；
 *   只管"问题问到了哪些表"。
 *
 * 【主要 API】
 *   retrieve(question, schema) → SchemaRetrievalResult(tables, schemaText, fallback)
 *     召回不到时 fallback=true，自动回退读全表结构。
 *   enrich(base, schema, hintTables) → 混合召回：把 few-shot SQL 里出现的表名补回来。
 *   extractTables(sql) → 静态方法：从 SQL 里抽 FROM/JOIN 后面的表名（hybrid grounding 用）。
 *
 * 【对比"全表注入"】
 *   旧方案把 100+ 张表全塞进 Prompt → 太长 → AI 被干扰 → SQL 错误 + 响应慢。
 *   本方案只注入相关表 → Prompt 短 → AI 聚焦 → 更准更快。
 *
 * 【关系补表（hybrid grounding）】
 *   召回后沿 FK 关系再"跳一跳"补 4 张以内的关联表，再把总量限在 15 张以内；
 *   防止简单问题带出整张关系网。
 *
 * 【何时回退】
 *   向量库未初始化 / Embedding 不可用 时，回退读取全部表结构（保证仍可用）。
 */
@Component
public class SchemaRetrievalStrategy {

    /** 关系补表最多增加的表数量，避免一条简单问题带出整张关系网。 */
    private static final int MAX_RELATED_TABLES = 4;

    /** 最终注入 Prompt 的表数量上限。原始召回表优先保留。 */
    private static final int MAX_SCHEMA_TABLES = 15;

    private final TableSchemaService tableSchemaService;

    public SchemaRetrievalStrategy(TableSchemaService tableSchemaService) {
        this.tableSchemaService = tableSchemaService;
    }

    /**
     * @param question 用户自然语言问题
     * @param schema   数据库 schema 名
     * @return 召回结果与注入用 DDL
     */
    public SchemaRetrievalResult retrieve(String question, String schema) {
        List<String> tables;
        boolean fallback;
        try {
            tables = tableSchemaService.findRelevantTables(question);
        } catch (Exception e) {
            // 向量库不可用（如 Embedding 服务未启动）：回退读全表
            tables = List.of();
        }
        if (tables.isEmpty()) {
            fallback = true;
            return new SchemaRetrievalResult(List.of(), tableSchemaService.readTableSchema(schema), true);
        }

        // 先按问题召回，再沿真实表关联关系补一跳。
        // 原始召回表优先保留；关联表只补少量，防止简单问题带出整张关系网。
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        tables.stream()
                .filter(Objects::nonNull)
                .map(String::toLowerCase)
                .filter(t -> !t.isBlank())
                .forEach(merged::add);

        List<String> relatedTables = tableSchemaService.findRelatedTables(tables);
        relatedTables.stream()
                .filter(Objects::nonNull)
                .map(String::toLowerCase)
                .filter(t -> !t.isBlank())
                .filter(t -> !merged.contains(t))
                .limit(MAX_RELATED_TABLES)
                .forEach(merged::add);

        List<String> limitedTables = merged.stream()
                .limit(MAX_SCHEMA_TABLES)
                .toList();
        return new SchemaRetrievalResult(
                limitedTables,
                tableSchemaService.readTableSchema(schema, limitedTables),
                false);
    }

    private static final Pattern TABLE_REF =
            Pattern.compile("\\b(?:FROM|JOIN)\\s+([A-Za-z_][A-Za-z0-9_]*)", Pattern.CASE_INSENSITIVE);

    /**
     * 从一条 SQL 中抽取被引用的物理表名（FROM / JOIN 之后的标识符）。
     * 用于混合召回：few-shot 范例 SQL 是"真实写法"，其表名即为该问题模式应当注入的表。
     */
    public static List<String> extractTables(String sql) {
        if (sql == null || sql.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        Matcher m = TABLE_REF.matcher(sql);
        while (m.find()) out.add(m.group(1).toLowerCase());
        return out;
    }

    /**
     * 混合召回（hybrid grounding）：用 few-shot 命中范例 SQL 中的真实表名反哺 schema 检索。
     *
     * 【解决的问题】纯向量相似度召回时，"设备运行时间轴"类问题常被 downtime_reason_duration /
     * flw_re_ins_dev 等"停机"相关表抢位，主表 device_operation_report 被挤出 top-K，
     * 导致 LLM 拿不到主表 DDL 而生成错误 SQL（如 SELECT ... WHERE 1=0）。
     *
     * 【策略】仅【追加】表名、绝不删减向量召回结果 —— 召回 misses 时补回主表，召回准时不引入噪声。
     * 表名统一转小写去重；readTableSchema 只读 information_schema 中真实存在的表，安全无注入风险。
     */
    public SchemaRetrievalResult enrich(SchemaRetrievalResult base, String schema, List<String> hintTables) {
        if (hintTables == null || hintTables.isEmpty()) return base;
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        base.tables().stream()
                .filter(Objects::nonNull)
                .map(String::toLowerCase)
                .filter(t -> !t.isBlank())
                .forEach(merged::add);
        hintTables.stream()
                .filter(Objects::nonNull)
                .map(String::toLowerCase)
                .filter(t -> !t.isBlank())
                .filter(t -> !merged.contains(t))
                .limit(Math.max(0, MAX_SCHEMA_TABLES - merged.size()))
                .forEach(merged::add);

        List<String> limitedTables = merged.stream().limit(MAX_SCHEMA_TABLES).toList();
        if (limitedTables.equals(base.tables())) return base;
        String schemaText = tableSchemaService.readTableSchema(schema, limitedTables);
        return new SchemaRetrievalResult(limitedTables, schemaText, base.fallback());
    }
}
