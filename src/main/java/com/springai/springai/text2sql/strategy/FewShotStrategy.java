package com.springai.springai.text2sql.strategy;

import com.springai.springai.smalldemo.service.TableSchemaService;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Few-shot（少样本）范例注入 —— 企业 NL2SQL 性价比第一环（与 Schema RAG 并列）。
 *
 * 【做什么】
 *   把历史沉淀的 (自然语言问题, 干净SQL) 范例向量化入库；提问时检索语义最相似的几条，
 *   拼进 Prompt 当 in-context example，让 AI "照葫芦画瓢"而不是凭空猜表/字段/写法。
 *
 * 【不做什么】
 *   不做表结构召回（那是 SchemaRetrievalStrategy）、不做术语注入（GlossaryStrategy）；
 *   只管"哪几条 SQL 写法最值得让 AI 看到"。
 *
 * 【主要 API】
 *   build(question, enabled) → FewShotResult(promptSection, matchedExemplars)
 *
 * 【降噪（关键）】
 *   - 过滤 lookup/bookkeeping 类范例（全表扫描、单周期、时刻定位等"记账型"查询）
 *   - 上限 4 条：过多会稀释核心范例信号
 *
 * 【存储约定】
 *   content=自然语言问题（被相似度检索命中）；metadata.sql=干净SQL（不参与检索，prompt 拼接时取出）。
 *   这是企业标准做法：检索键与展示内容分离。
 */
@Component
public class FewShotStrategy {

    private final TableSchemaService tableSchemaService;

    public FewShotStrategy(TableSchemaService tableSchemaService) {
        this.tableSchemaService = tableSchemaService;
    }

    /**
     * @param question 用户问题
     * @param enabled  是否启用 few-shot（开关便于 A/B 对照）
     * @return 注入文本段 + 命中明细
     */
    public FewShotResult build(String question, boolean enabled) {
        if (!enabled) {
            return new FewShotResult("", List.of());
        }

        List<Document> exemplars = tableSchemaService.findRelevantExemplars(question);

        StringBuilder sb = new StringBuilder();
        sb.append("【参考范例】(以下为语义最相似的已有 (问题,SQL) 对，请参考其写法风格与表/字段用法，不要原样照搬，按用户新问题改写)：\n");

        List<Map<String, Object>> matched = new ArrayList<>();
        int idx = 1;
        int maxFewShot = 4; // few-shot 上限：过多会稀释核心范例信号
        for (Document doc : exemplars) {
            if (idx > maxFewShot) break;
            Map<String, Object> meta = doc.getMetadata();
            // 过滤 lookup/bookkeeping 类（全表扫描、单周期、时刻定位等），避免污染召回信号
            String category = meta.get("category") == null ? "" : String.valueOf(meta.get("category"));
            if ("lookup".equals(category)) continue;

            String q = doc.getText();
            String sql = meta.get("sql") == null ? "" : String.valueOf(meta.get("sql"));
            String intent = meta.get("intent") == null ? "" : String.valueOf(meta.get("intent"));

            sb.append("示例").append(idx++).append("：\n");
            sb.append("  问题：").append(q).append("\n");
            if (!intent.isEmpty()) sb.append("  意图：").append(intent).append("\n");
            sb.append("  SQL：").append(sql).append("\n\n");

            Map<String, Object> m = new HashMap<>();
            m.put("question", q);
            m.put("sql", sql);
            m.put("intent", intent);
            m.put("category", category);
            matched.add(m);
        }
        return new FewShotResult(sb.toString(), matched);
    }
}
