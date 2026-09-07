package com.springai.springai.safe;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 安全 SQL 校验器（L5/LLM05 输出处理 + L6/LLM06 过度代理）
 *
 * 目标：Text-to-SQL（文本转SQL）场景里，确保 AI 生成的 SQL 只能是只读查询，
 * 任何写操作 / 堆叠查询 / 系统表查询都被拦截。
 *
 * 【修复你项目 TextToSqlController 的真实漏洞】
 * 原实现用 `normalized.startsWith(dangerous)` —— 只看"开头"。
 * 于是 `SELECT * FROM device; DROP TABLE device;` 因为"以 SELECT 开头"而放行，
 * 第二句 DROP 直接把表删了。本实现改为"全文扫描危险关键字 + 分号切分计数"，
 * 彻底堵住堆叠查询绕过。
 *
 * 对应你之前说的两层防御：
 *   - LLM 层 prompt 限 SELECT（软）
 *   - 代码层关键字拦截（硬）—— 本类就是硬防线，且比原实现更严。
 */
public class SafeSqlValidator {

    /** 允许的只读开头 */
    private static final Set<String> ALLOWED_PREFIX = Set.of("select", "with");

    /** 全文禁止的危险关键字（任何位置出现都不许） */
    private static final Set<String> FORBIDDEN = Set.of(
            "insert", "update", "delete", "drop", "truncate", "alter", "create", "grant", "revoke",
            "merge", "replace", "exec", "execute", "call", "commit", "rollback", "savepoint", "vacuum"
    );

    /** 系统表 / 元数据表（禁止被查，防信息收集） */
    private static final Pattern SYSTEM_TABLE = Pattern.compile(
            "\\b(pg_|information_schema|mysql\\.|sqlite_master|sys\\.)", Pattern.CASE_INSENSITIVE);

    /** 提取 from/join 后的表名，用于白名单校验 */
    private static final Pattern TABLE_REF = Pattern.compile(
            "\\b(from|join)\\s+([a-zA-Z_][\\w$.]*)", Pattern.CASE_INSENSITIVE);

    private final Set<String> allowedTables;

    public SafeSqlValidator() {
        this.allowedTables = Set.of();
    }

    /** 带表白名单的构造（生产推荐：只允许查业务表，禁查系统表以外的任何表） */
    public SafeSqlValidator(Set<String> allowedTables) {
        this.allowedTables = new HashSet<>(allowedTables);
    }

    public static class Result {
        public final boolean ok;
        public final String reason;
        public Result(boolean ok, String reason) {
            this.ok = ok;
            this.reason = reason;
        }
    }

    public Result validate(String sql) {
        if (sql == null || sql.isBlank()) {
            return new Result(false, "SQL 为空");
        }
        // 去掉注释与多余空白（防止 -- 注释藏危险语句）
        String normalized = sql.replaceAll("--[^\\n]*", " ")
                .replaceAll("/\\*.*?\\*/", " ")
                .replaceAll("\\s+", " ")
                .trim();
        String lower = normalized.toLowerCase();

        // 1. 必须以只读语句开头
        boolean startsReadOnly = false;
        for (String p : ALLOWED_PREFIX) {
            if (lower.equals(p) || lower.startsWith(p + " ")) {
                startsReadOnly = true;
                break;
            }
        }
        if (!startsReadOnly) {
            return new Result(false, "只允许 SELECT / WITH 开头的只读查询（当前: " + firstWord(lower) + "）");
        }

        // 2. 全文禁止危险关键字（关键修复：不再只看开头）
        for (String kw : FORBIDDEN) {
            if (lower.matches(".*\\b" + kw + "\\b.*")) {
                return new Result(false, "检测到禁止的关键字: " + kw.toUpperCase());
            }
        }

        // 3. 堆叠查询拦截：分号切分后存在多条非空语句
        String[] parts = normalized.split(";");
        long nonEmpty = Arrays.stream(parts).filter(s -> !s.trim().isEmpty()).count();
        if (nonEmpty > 1) {
            return new Result(false, "检测到堆叠查询（多条语句），已拦截");
        }

        // 4. 系统表 / 元数据表拦截
        if (SYSTEM_TABLE.matcher(normalized).find()) {
            return new Result(false, "禁止查询系统表/元数据表");
        }

        // 5. 表白名单（可选）
        if (!allowedTables.isEmpty()) {
            Matcher tm = TABLE_REF.matcher(normalized);
            while (tm.find()) {
                String t = tm.group(2).split("\\.")[0].toLowerCase();
                if (!allowedTables.contains(t)) {
                    return new Result(false, "表不在白名单: " + t);
                }
            }
        }
        return new Result(true, "OK");
    }

    private static String firstWord(String s) {
        int i = s.indexOf(' ');
        return i < 0 ? s : s.substring(0, i);
    }
}
