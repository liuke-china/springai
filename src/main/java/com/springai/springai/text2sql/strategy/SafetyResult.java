package com.springai.springai.text2sql.strategy;

/**
 * SQL 安全校验产物（方案④ 安全护栏）—— 第二层兜底。
 * 代码级拦截危险 SQL，不只靠 Prompt 软约束。
 */
public record SafetyResult(
        boolean safe,      // 是否通过校验
        String riskLevel,  // SAFE / DANGEROUS
        String riskNote    // 说明（通过原因 or 拦截原因）
) {
}
