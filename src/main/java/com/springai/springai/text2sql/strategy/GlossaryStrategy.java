package com.springai.springai.text2sql.strategy;

import com.springai.springai.smalldemo.service.TableSchemaService;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务术语 / 字段枚举字典（Glossary）—— 防"枚举值猜错"。
 *
 * 【做什么】
 *   把业务术语与字段枚举（来自 IMS 代码 / 字典表）向量化入库；提问时检索相关术语，
 *   拼进 Prompt，强制 AI 按真实枚举值理解问题，杜绝臆测。
 *
 * 【不做什么】
 *   不管 SQL 写法（FewShotStrategy）、不管表关联（KnowledgeAugmentStrategy）；
 *   只管"业务词 → 字段/枚举值"的语义翻译。
 *
 * 【主要 API】
 *   build(question, enabled) → GlossaryResult(promptSection, matchedTerms)
 *
 * 【示例痛点】
 *   IMS device.state 是枚举（0=离线 1=在线 2=故障 3=维修中）。
 *   用户问"查所有在线设备"时，没这段提示 AI 容易写成 state='在线' 或 state=2 瞎猜。
 */
@Component
public class GlossaryStrategy {

    private final TableSchemaService tableSchemaService;

    public GlossaryStrategy(TableSchemaService tableSchemaService) {
        this.tableSchemaService = tableSchemaService;
    }

    /**
     * @param question 用户问题
     * @param enabled  是否启用（开关便于 A/B 对照）
     * @return 注入文本段 + 命中术语明细
     */
    public GlossaryResult build(String question, boolean enabled) {
        if (!enabled) {
            return new GlossaryResult("", List.of());
        }

        List<Document> docs = tableSchemaService.findDocumentsByType("glossary", question, 5);

        StringBuilder sb = new StringBuilder();
        sb.append("【业务术语与字段枚举字典】(请严格按以下业务定义理解用户问题中的术语与枚举值，不要臆测)：\n");

        List<Map<String, Object>> matched = new ArrayList<>();
        for (Document doc : docs) {
            Map<String, Object> meta = doc.getMetadata();
            String term = String.valueOf(meta.getOrDefault("term", ""));
            String definition = String.valueOf(meta.getOrDefault("definition", ""));
            String tables = String.valueOf(meta.getOrDefault("tables", ""));

            sb.append("- 术语【").append(term).append("】").append(definition);
            if (!tables.isEmpty()) sb.append(" (相关表: ").append(tables).append(")");
            sb.append("\n");

            Map<String, Object> m = new HashMap<>();
            m.put("term", term);
            m.put("definition", definition);
            m.put("tables", tables);
            matched.add(m);
        }
        return new GlossaryResult(sb.toString(), matched);
    }
}
