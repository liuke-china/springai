package com.springai.springai.text2sql;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行边界：所有真正打到数据库的 SQL 都要经过这一层。
 *
 * 三件事：
 *   1. EXPLAIN 预检（拦截语法错、列不存在、cost 过高）
 *   2. 限制返回行数（maxRows，避免一张表查爆内存）
 *   3. 限制查询超时（queryTimeoutSeconds，避免慢查询拖死连接池）
 *
 * 为什么独立成类：自纠错循环（SelfCorrectionStrategy）和主编排器（Orchestrator）都要执行 SQL，
 * 之前自纠错用的是 jdbcTemplate.queryForList 直接执行，绕过了 EXPLAIN 预检——
 * 现在两边都走这里，保证执行边界一致。
 */
@Slf4j
@Component
public class ExecutionGuard {

    /** EXPLAIN 估算成本上限：超过此值视为"扫太多行"直接拒绝，避免全表扫把库打挂 */
    private static final double MAX_PLAN_COST = 100_000;

    /** 单次执行最大行数硬上限（请求可更小，但不能再大） */
    private static final int MAX_ROWS_LIMIT = 10_000;

    /** 单次查询超时硬上限（秒），防止请求里写一个 9999 把连接池拖死 */
    private static final int MAX_TIMEOUT_SECONDS = 60;

    /** EXPLAIN 自身的超时硬上限：再慢也不能超过 10s，否则视为失败 */
    private static final int MAX_EXPLAIN_TIMEOUT = 10;

    private final JdbcTemplate jdbcTemplate;

    public ExecutionGuard(@Qualifier("readOnlyJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 限行 + 限时 + EXPLAIN 预检 + 执行。
     * 任一步失败抛 IllegalStateException，调用方按错误分类处理。
     */
    public List<Map<String, Object>> executeBounded(String sql, TextToSqlRequest req) {
        int maxRows = clamp(req.getMaxRows(), 1, MAX_ROWS_LIMIT);
        int timeout = clamp(req.getQueryTimeoutSeconds(), 1, MAX_TIMEOUT_SECONDS);
        explainValidate(sql, timeout);
        return jdbcTemplate.query(connection -> {
            var statement = connection.prepareStatement(sql);
            statement.setMaxRows(maxRows);
            statement.setQueryTimeout(timeout);
            return statement;
        }, this::rowToMap);
    }

    /**
     * 用 PG 原生 EXPLAIN (FORMAT JSON) 预检：
     *   - SQL 语法/对象错 → EXPLAIN 直接报错（被我们转成 IllegalStateException 抛回）
     *   - 估算 cost > MAX_PLAN_COST → 拒绝执行
     */
    private void explainValidate(String sql, int timeoutSeconds) {
        String cleaned = sql.strip().replaceAll(";\\s*$", "");
        int explainTimeout = Math.max(1, Math.min(timeoutSeconds, MAX_EXPLAIN_TIMEOUT));
        try {
            List<Map<String, Object>> plan = jdbcTemplate.query(connection -> {
                var st = connection.prepareStatement("EXPLAIN (FORMAT JSON) " + cleaned);
                st.setQueryTimeout(explainTimeout);
                return st;
            }, this::rowToMap);
            double totalCost = extractTotalCost(plan);
            if (totalCost > MAX_PLAN_COST) {
                throw new IllegalStateException(String.format(
                        "查询成本过高被拦截：估算总成本 %.0f 超过阈值 %.0f",
                        totalCost, MAX_PLAN_COST));
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("SQL 预检未通过（EXPLAIN 失败）：" + e.getMessage());
        }
    }

    /** 把 EXPLAIN JSON 第一层 Plan.Total Cost 抽出来 */
    @SuppressWarnings("unchecked")
    private double extractTotalCost(List<Map<String, Object>> plan) {
        if (plan == null || plan.isEmpty()) return 0;
        Object planObj = plan.get(0).get("Plan");
        if (planObj instanceof Map) {
            Object cost = ((Map<String, Object>) planObj).get("Total Cost");
            if (cost instanceof Number) return ((Number) cost).doubleValue();
        }
        return 0;
    }

    /** ResultSet 一行 → Map（用 LinkedHashMap 保留列序） */
    private Map<String, Object> rowToMap(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        var metadata = rs.getMetaData();
        var row = new LinkedHashMap<String, Object>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            row.put(metadata.getColumnLabel(i), rs.getObject(i));
        }
        return row;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(v, hi));
    }
}