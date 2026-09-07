package com.springai.springai.text2sql.strategy;

import java.util.List;
import java.util.Map;

/**
 * 表关联 + 接口映射（方案⑧）的产物：
 * - promptSection：拼进 Prompt 的【已知表关联】+【已知页面/接口】文本段
 * - matchedForeignKeys：命中的 FK 命中明细，便于观察"AI 参考了哪些 JOIN 模式"
 * - matchedInterfaceMaps：命中的接口映射命中明细，便于观察"AI 参考了哪些菜单/页面"
 */
public record KnowledgeAugmentResult(
        String promptSection,
        List<Map<String, Object>> matchedForeignKeys,
        List<Map<String, Object>> matchedInterfaceMaps
) {
}
