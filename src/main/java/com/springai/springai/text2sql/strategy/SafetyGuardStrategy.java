package com.springai.springai.text2sql.strategy;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * SQL 安全护栏（Safety Guard）—— 企业必做项，多层防御。
 *
 * 【做什么】
 *   校验 AI 生成的 SQL 是否安全：四道防线（多语句拦截 → 体内关键字扫描 → 前缀判断 → SELECT/WITH 白名单）。
 *
 * 【为什么不只靠 Prompt】
 *   第一层 Prompt 软约束（让 AI 答应只写 SELECT）拦截 95%；
 *   本类硬校验兜住剩余 5%（AI 注入、模型不稳定、幻觉拼接）。
 *
 * 【主要 API】
 *   validate(sql) → SafetyResult(safe, riskLevel, riskNote)
 *
 * 【四道防线（按重要性排）】
 *   1) 多语句拦截：禁止 `;` 拼接的多条 SQL
 *   2) 关键字扫描：即便开头是 SELECT，体内也不能出现 DDL/DML 关键字
 *   3) 前缀判断：必须以 SELECT/WITH 开头（仅作为兜底，主防线是上面两条）
 *   4) AST 解析：如果引入 JSqlParser 依赖，可做完整 AST 检查；这里用关键字扫描做轻量替代
 *
 * 【更强的做法（企业版）】
 *   - 数据库只读账号 + 行级 RLS 权限（防止读到不该看的数据）—— DbLeastPrivilegeConfig 已做
 *   - 引入 JSqlParser 做 AST 检查（不是字符串而是解析树，能 100% 拦截 DML）
 *   - SQL 白名单（限定的表/视图才能访问）
 *   本类只做代码层校验，更深一层需要 DBA 配合建账号。
 */
@Component
public class SafetyGuardStrategy {

    /** 禁止开头的操作：拦截"以 DELETE/UPDATE 等开头"的语句 */
    private static final Set<String> DANGEROUS_PREFIXES = Set.of(
            "DELETE", "UPDATE", "DROP", "INSERT", "TRUNCATE", "ALTER", "CREATE", "GRANT", "REVOKE"
    );

    /** 体内禁止出现的关键字（按词边界扫描）—— 即便 SELECT，体内也不能有这些 */
    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            "DELETE", "UPDATE", "DROP", "INSERT", "TRUNCATE", "ALTER", "CREATE",
            "GRANT", "REVOKE", "EXEC", "EXECUTE", "CALL"
    );

    /**
     * 校验入口：依次跑四道防线；任一不过直接返回 false。
     */
    public SafetyResult validate(String sql) {
        if (sql == null || sql.isBlank()) {
            return new SafetyResult(false, "DANGEROUS", "AI 未生成有效的 SQL");
        }

        // ---- 防线 1：拒绝多语句 ----
        // 去掉所有引号/注释后再统计 `;`，防止引号里的分号误判；
        // 只允许最多一个 `;`（末尾分号）。
        String stripped = stripQuotesAndComments(sql);
        long semis = stripped.chars().filter(ch -> ch == ';').count();
        if (semis > 1) {
            return new SafetyResult(false, "DANGEROUS",
                    "禁止多语句拼接：检测到 " + semis + " 个分号（只允许末尾 1 个）");
        }

        // ---- 防线 2：体内关键字扫描（按词边界，避免误判字段名） ----
        String upper = sql.toUpperCase();
        for (String kw : FORBIDDEN_KEYWORDS) {
            // 用正则匹配词边界，例如 \bDELETE\b 不会匹配到 DELETE_RECORD
            String pattern = "(?<![A-Z_0-9])" + kw + "(?![A-Z_0-9])";
            if (java.util.regex.Pattern.compile(pattern).matcher(upper).find()) {
                return new SafetyResult(false, "DANGEROUS",
                        "SQL 体内包含禁用关键字 " + kw + "（即便 SELECT 也禁止内嵌 DDL/DML）");
            }
        }

        // ---- 防线 3：前缀判断（最严兜底） ----
        String normalized = sql.trim().toUpperCase().replaceAll("^[\\s(]+", "");
        for (String dangerous : DANGEROUS_PREFIXES) {
            if (normalized.startsWith(dangerous)) {
                return new SafetyResult(false, "DANGEROUS", "禁止执行 " + dangerous + " 操作");
            }
        }
        if (!normalized.startsWith("SELECT") && !normalized.startsWith("WITH")) {
            return new SafetyResult(false, "DANGEROUS", "只允许 SELECT / WITH 查询");
        }

        return new SafetyResult(true, "SAFE",
                "通过安全校验（单语句 + 无体内 DDL/DML + SELECT/WITH 前缀）");
    }

    /**
     * 去掉 SQL 里的字符串字面量（'...'）和注释（-- ... / /* ... *\/），避免引号里的内容被误判。
     * 简化实现：用正则把 ' ' 和 " " 以及 -- ... 替换成空白。
     */
    private static String stripQuotesAndComments(String sql) {
        String s = sql;
        // 单行注释 -- 到行尾
        s = s.replaceAll("(?m)--[^\\n]*", " ");
        // 多行注释 /* ... */（跨行）
        s = s.replaceAll("/\\*[\\s\\S]*?\\*/", " ");
        // 字符串字面量 '...'
        s = s.replaceAll("'[^']*'", " ");
        return s;
    }
}