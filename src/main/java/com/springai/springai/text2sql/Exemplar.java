package com.springai.springai.text2sql;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * NL2SQL few-shot 范例 DTO
 *
 * 对应 text2sql-refs 下生成的 device_op_report_exemplars.json 等文件结构。
 * 每条 Exemplar = 一个 (自然语言问题, 干净SQL) 对，作为 few-shot 范例注入 Prompt。
 *
 * 字段命名与生成 JSON 的 key 完全一致（用 @JsonProperty 锚定，避免 snake/camel 错位）。
 */
public class Exemplar {

    @JsonProperty("id")
    private String id;

    @JsonProperty("mapper")
    private String mapper;

    @JsonProperty("tables")
    private List<String> tables;

    @JsonProperty("category")
    private String category;

    @JsonProperty("difficulty")
    private String difficulty;

    @JsonProperty("intent")
    private String intent;

    @JsonProperty("question")
    private String question;

    @JsonProperty("sql")
    private String sql;

    @JsonProperty("sql_raw")
    private String sqlRaw;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getMapper() { return mapper; }
    public void setMapper(String mapper) { this.mapper = mapper; }

    public List<String> getTables() { return tables; }
    public void setTables(List<String> tables) { this.tables = tables; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }

    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getSql() { return sql; }
    public void setSql(String sql) { this.sql = sql; }

    public String getSqlRaw() { return sqlRaw; }
    public void setSqlRaw(String sqlRaw) { this.sqlRaw = sqlRaw; }

    /**
     * JSON 顶层包装：{"exemplars":[...], "total":52, ...}
     * 只取 exemplars 数组，其余字段（total/category_summary 等）由 Jackson 忽略。
     */
    public static class ExemplarFile {
        @JsonProperty("exemplars")
        private List<Exemplar> exemplars;

        public List<Exemplar> getExemplars() { return exemplars; }
        public void setExemplars(List<Exemplar> exemplars) { this.exemplars = exemplars; }
    }
}
