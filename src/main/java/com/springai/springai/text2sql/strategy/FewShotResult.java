package com.springai.springai.text2sql.strategy;

import java.util.List;
import java.util.Map;

/**
 * few-shot 范例检索（方案②）的产物：
 * - promptSection：拼进 Prompt 的【参考范例】文本段（空字符串表示本次未注入）
 * - matchedExemplars：命中的 (问题,SQL,意图,类别) 列表，便于观察"AI 翻了哪几条已有写法"
 */
public record FewShotResult(
        String promptSection,
        List<Map<String, Object>> matchedExemplars
) {
}
