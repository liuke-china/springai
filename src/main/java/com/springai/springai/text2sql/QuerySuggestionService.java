package com.springai.springai.text2sql;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 查询建议（Query Suggestions）—— 类似"百度下拉框"，给用户提问时的前缀提示。
 *
 * 【做法】
 * 用户输入"稼动率"，系统从 exemplar 库（前缀命中）中找相似问题返回，方便用户复用已验证的问法。
 * 这能解决"用户不会提问"的真实问题：业务人员第一次不知道该用什么词。
 *
 * 【不需要 LLM】
 * PG `ILIKE` + 表内容包含关系足够，不需要再调一次模型，省钱省延迟。
 */
@Slf4j
@Service
public class QuerySuggestionService {

    private final JdbcTemplate jdbcTemplate;

    public QuerySuggestionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 根据输入前缀返回建议列表（前缀匹配 exemplar 的 question 字段）。
     * 返回字段：question / intent（业务意图）/ mapper（最相关的 Mapper 名）。
     *
     * @param prefix 用户已输入的字符（至少 1 个）
     * @param limit  返回条数上限
     */
    public List<Map<String, Object>> suggest(String prefix, int limit) {
        if (prefix == null || prefix.isBlank()) return List.of();
        int top = Math.max(1, Math.min(limit, 20));
        // PGVector 用 JSONB metadata 存 SQL/question；用 ILIKE 做大小写不敏感前缀匹配
        String sql = """
            SELECT DISTINCT
                metadata->>'sql' AS sql,
                metadata->>'intent' AS intent,
                metadata->>'mapper' AS mapper,
                metadata->>'category' AS category
            FROM vector_store
            WHERE metadata->>'type' = 'exemplar'
              AND content ILIKE ?
            LIMIT ?
            """;
        try {
            return jdbcTemplate.queryForList(sql, prefix + "%", top);
        } catch (Exception e) {
            log.warn("查询建议失败: {}", e.getMessage());
            return List.of();
        }
    }
}