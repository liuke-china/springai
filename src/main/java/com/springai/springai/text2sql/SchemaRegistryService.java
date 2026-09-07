package com.springai.springai.text2sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Schema Registry（版本化快照）：给 PG 表结构打版本号，记录每次向量化的"快照"。
 *
 * 【为什么需要】
 *  - 数据库表结构会变（加列、改字段、删表）
 *  - 知识库（few-shot、glossary、metrics）都可能依赖特定版本的表结构
 *  - 出问题时能反查"那是哪个版本的表结构下生成的 SQL"
 *  - 上线前可对比"上次快照"和"现在快照"，自动检测 Schema 变化
 *
 * 【表结构】
 *   text2sql_schema_snapshots
 *   (id, version, schema_name, table_count, snapshot_json, created_at)
 *
 * 【做法】
 *   - 每次 indexSchema 之前生成一个新快照（version = yyyyMMddHHmmss）
 *   - snapshot_json = {table_name -> [column_list]}，方便 diff
 */
@Slf4j
@Service
public class SchemaRegistryService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SchemaRegistryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        initTable();
    }

    private void initTable() {
        String ddl = """
            CREATE TABLE IF NOT EXISTS text2sql_schema_snapshots (
                id           BIGSERIAL PRIMARY KEY,
                version      VARCHAR(40) NOT NULL UNIQUE,
                schema_name  VARCHAR(40) NOT NULL,
                table_count  INT,
                snapshot_json JSONB,
                created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;
        try {
            jdbcTemplate.execute(ddl);
        } catch (Exception e) {
            log.warn("Schema 快照表初始化失败: {}", e.getMessage());
        }
    }

    /**
     * 给指定 schema 打一个版本化快照，写入快照表。
     * 返回版本号（yyyyMMddHHmmss）。
     */
    public String snapshot(String schema) {
        String version = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String sql = """
                SELECT c.table_name, c.column_name, c.data_type
                FROM information_schema.columns c
                WHERE c.table_schema = ?
                  AND c.table_name NOT IN ('vector_store','pg_stat_statements',
                       'text2sql_audit_log','text2sql_schema_snapshots')
                ORDER BY c.table_name, c.ordinal_position
                """;
        List<Map<String, Object>> cols = jdbcTemplate.queryForList(sql, schema);
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : cols) {
            grouped.computeIfAbsent((String) row.get("table_name"), k -> new ArrayList<>())
                   .add(row.get("column_name") + ":" + row.get("data_type"));
        }
        try {
            String json = objectMapper.writeValueAsString(grouped);
            jdbcTemplate.update("""
                INSERT INTO text2sql_schema_snapshots (version, schema_name, table_count, snapshot_json)
                VALUES (?, ?, ?, ?::jsonb)
                ON CONFLICT (version) DO NOTHING
                """,
                version, schema, grouped.size(), json);
            log.info("Schema 快照已落库: version={}, schema={}, table_count={}",
                    version, schema, grouped.size());
        } catch (Exception e) {
            log.warn("Schema 快照写入失败: {}", e.getMessage());
        }
        return version;
    }

    /**
     * 取最近 N 条快照记录，用于前端展示或回溯。
     */
    public List<Map<String, Object>> recent(int limit) {
        return jdbcTemplate.queryForList(
            "SELECT version, schema_name, table_count, created_at " +
            "FROM text2sql_schema_snapshots ORDER BY created_at DESC LIMIT ?",
            Math.max(1, Math.min(limit, 50)));
    }
}