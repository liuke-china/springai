package com.springai.springai.text2sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 审计日志：把每一次查询的（问题/SQL/结果/错误分类/耗时/命中数）落库，方便出问题时追溯。
 *
 * 【为什么需要】
 *  - 出问题时能直接查到当时 AI 召回了几条 few-shot、用了什么 SQL
 *  - 合规要求：谁、什么时间、问了什么、答了什么
 *  - 后续做用户行为分析（哪些问题经常被问到、哪些总是答错）
 *
 * 【表结构】
 *  表名 text2sql_audit_log，启动时自动 CREATE（如不存在）
 *  字段：id, request_id, question, sql, safe, error_type, exec_ok, exec_error,
 *        row_count, latency_ms, matched_metrics(jsonb), user_id, role, created_at
 *
 * 注意：写入失败不影响主链路（包 try/catch，只打 warn 日志）。
 */
@Slf4j
@Service
public class TextToSqlAuditService {

    private final JdbcTemplate jdbcTemplate;
    /** Jackson：把命中的业务口径 List 转成 JSON 字符串存进 JSONB 列 */
    private final ObjectMapper objectMapper;

    public TextToSqlAuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        initTable();
    }

    /**
     * 自动建表：用 PG 原生 JSONB 类型存命中明细；多次启动不会重复建。
     */
    private void initTable() {
        String ddl = """
            CREATE TABLE IF NOT EXISTS text2sql_audit_log (
                id           BIGSERIAL PRIMARY KEY,
                request_id   VARCHAR(64)  NOT NULL,
                question     TEXT,
                sql          TEXT,
                safe         BOOLEAN,
                error_type   VARCHAR(40),
                exec_ok      BOOLEAN,
                exec_error   TEXT,
                row_count    INT,
                latency_ms   INT,
                matched_metrics JSONB,
                user_id      VARCHAR(64),
                role         VARCHAR(64),
                created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;
        try {
            jdbcTemplate.execute(ddl);
        } catch (Exception e) {
            log.warn("审计表初始化失败（首次启动或无权限）: {}", e.getMessage());
        }
    }

    /**
     * 写一条审计。失败不抛异常，避免污染主链路。
     *
     * @param req           用户原始请求（含 question）
     * @param finalSql      最终采用的 SQL（可能 null：未生成成功）
     * @param errorType     错误分类（NONE/SAFETY_BLOCKED/...）
     * @param execOk        是否执行成功
     * @param execError     执行错误信息（成功则为 null）
     * @param rowCount      实际返回行数
     * @param latencyMs     端到端耗时（毫秒）
     * @param matchedMetrics 命中的业务口径明细（List，可能为空）；会原样存进 matched_metrics 列，方便回溯"当时 AI 用了哪条口径"
     * @param userId        用户 ID（可空）
     * @param role          角色（可空）
     */
    public void write(TextToSqlRequest req,
                      String finalSql,
                      TextToSqlTrace.SqlErrorType errorType,
                      boolean execOk,
                      String execError,
                      int rowCount,
                      long latencyMs,
                      List<Map<String, Object>> matchedMetrics,
                      String userId,
                      String role) {
        try {
            String requestId = UUID.randomUUID().toString();
            // 把命中的业务口径转成 JSON 字符串存进 JSONB 列（之前写死 "[]"，审计看不到任何口径，等于这个功能白做了）
            String matchedJson = "[]";
            if (matchedMetrics != null && !matchedMetrics.isEmpty()) {
                try {
                    matchedJson = objectMapper.writeValueAsString(matchedMetrics);
                } catch (Exception ignore) {
                    matchedJson = "[]";
                }
            }
            jdbcTemplate.update("""
                INSERT INTO text2sql_audit_log
                (request_id, question, sql, safe, error_type, exec_ok, exec_error,
                 row_count, latency_ms, matched_metrics, user_id, role, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """,
                requestId,
                req == null ? null : req.getQuestion(),
                finalSql,
                errorType != TextToSqlTrace.SqlErrorType.SAFETY_BLOCKED,
                errorType == null ? null : errorType.name(),
                execOk,
                execError,
                rowCount,
                (int) latencyMs,
                matchedJson,
                userId,
                role,
                LocalDateTime.now());
        } catch (Exception e) {
            log.warn("审计写入失败（不影响主链路）: {}", e.getMessage());
        }
    }

    /**
     * 查询最近的审计记录（用于 debug 或前端展示）。
     */
    public List<Map<String, Object>> recent(int limit) {
        return jdbcTemplate.queryForList(
            "SELECT request_id, question, sql, error_type, row_count, latency_ms, matched_metrics, created_at " +
            "FROM text2sql_audit_log ORDER BY created_at DESC LIMIT ?",
            Math.max(1, Math.min(limit, 200)));
    }
}