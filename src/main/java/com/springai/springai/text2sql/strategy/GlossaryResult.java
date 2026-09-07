package com.springai.springai.text2sql.strategy;

import java.util.List;
import java.util.Map;

/**
 * 业务术语 / 字段枚举字典（方案⑤）的产物：
 * - promptSection：拼进 Prompt 的【业务术语与字段枚举字典】文本段
 * - matchedTerms：命中的术语明细，便于观察"AI 参考了哪些业务定义"
 */
public record GlossaryResult(
        String promptSection,
        List<Map<String, Object>> matchedTerms
) {
}
