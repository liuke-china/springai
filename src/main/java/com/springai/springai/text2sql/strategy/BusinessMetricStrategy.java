package com.springai.springai.text2sql.strategy;

import com.springai.springai.demo.service.TableSchemaService;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务口径召回（Business Metric）—— "指标怎么算"的规则注入。
 *
 * 【做什么】
 *   召回项目里 OEE / 停机时长 / 良品率 等指标的标准计算方式（SQL 表达式、统计粒度、排除条件），
 *   拼进 Prompt 让 AI 不要自己瞎拼公式。
 *
 * 【为什么不并入 GlossaryStrategy】
 *   - glossary 管"业务词→字段名"翻译（OEE→oee_core_index_report.oee）
 *   - 业务口径管"指标怎么算"的规则（设备数量=COUNT(DISTINCT)，停机时长是否排除未结束）
 *   两者关注点不同，召回时关键词不同；分开便于单独 A/B 评测。
 *
 * 【主要 API】
 *   build(question, enabled) → BusinessMetricResult(promptSection, matchedMetrics)
 *
 * 【召回数据格式】
 *   content = metric + 同义词 + 定义（用于语义检索命中）
 *   metadata.expression / metadata.tables / metadata.source / metadata.confidence
 */
@Component
public class BusinessMetricStrategy {

    /** 召回条数上限：业务口径一般不会同时命中多个，取 3 就够 */
    private static final int TOP_K = 3;

    private final TableSchemaService tableSchemaService;

    public BusinessMetricStrategy(TableSchemaService tableSchemaService) {
        this.tableSchemaService = tableSchemaService;
    }

    /**
     * @param question 用户问题
     * @param enabled  开关
     * @return Prompt 文本 + 命中明细
     */
    public BusinessMetricResult build(String question, boolean enabled) {
        if (!enabled) return new BusinessMetricResult("", List.of());
        List<Document> docs = tableSchemaService.findDocumentsByType("business_metric", question, TOP_K);
        if (docs.isEmpty()) return new BusinessMetricResult("", List.of());

        StringBuilder sb = new StringBuilder();
        sb.append("【业务口径】以下指标在项目里有标准计算方式，请按 expression 写 SQL：\n");
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Document doc : docs) {
            Map<String, Object> meta = doc.getMetadata();
            String metric      = strOf(meta.get("metric"));
            String definition  = strOf(meta.get("definition"));
            String expression  = strOf(meta.get("expression"));
            String source      = strOf(meta.get("source"));
            String confidence  = strOf(meta.get("confidence"));

            sb.append("- 指标：").append(metric).append("\n");
            sb.append("  定义：").append(definition).append("\n");
            sb.append("  标准表达式：").append(expression).append("\n");
            if (!source.isEmpty()) sb.append("  来源：").append(source);
            if (!confidence.isEmpty()) sb.append("（置信度：").append(confidence).append("）");
            sb.append("\n\n");

            Map<String, Object> m = new HashMap<>();
            m.put("metric", metric);
            m.put("definition", definition);
            m.put("expression", expression);
            m.put("source", source);
            m.put("confidence", confidence);
            matched.add(m);
        }
        return new BusinessMetricResult(sb.toString(), matched);
    }

    private static String strOf(Object v) { return v == null ? "" : String.valueOf(v); }
}