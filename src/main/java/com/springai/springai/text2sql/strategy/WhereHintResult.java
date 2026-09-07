package com.springai.springai.text2sql.strategy;

import java.util.List;
import java.util.Map;

/**
 * 各表常用过滤字段（方案⑨ where_hint 检索）的产物。
 * 与 FewShotResult / GlossaryResult / BusinessMetricResult 形态一致：可注入 Prompt 的文本段 + 命中明细。
 */
public record WhereHintResult(
        String promptSection,
        List<Map<String, Object>> matchedTables
) {
}