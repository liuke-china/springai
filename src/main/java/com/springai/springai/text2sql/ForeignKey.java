package com.springai.springai.text2sql;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 表关联关系（Foreign Key / 跨表 JOIN 模式）DTO。
 *
 * 对应 text2sql-refs/ims_foreign_keys.json 结构。每条 = IMS 项目里实际出现过的一次
 * 跨表关联（来自 MyBatis XML JOIN / resultMap / 命名约定），向量化后 type='foreign_key' 入库，
 * 提问时按语义检索注入 Prompt，告诉 AI "项目里这些表就该这么 JOIN"，
 * 防止它凭直觉拼错 JOIN 条件或漏掉关键关联。
 *
 * 字段命名与生成 JSON 的 key 完全一致（用 @JsonProperty 锚定，避免 snake/camel 错位）。
 * 使用 Lombok @Data 自动生成 getter/setter，参照项目里其它 DTO 的现代风格。
 */
@Data
public class ForeignKey {

    @JsonProperty("from_table")
    private String fromTable;

    @JsonProperty("from_column")
    private String fromColumn;

    @JsonProperty("to_table")
    private String toTable;

    @JsonProperty("to_column")
    private String toColumn;

    @JsonProperty("relation")
    private String relation;

    @JsonProperty("confidence")
    private String confidence;

    @JsonProperty("evidence")
    private String evidence;

    @JsonProperty("join_hint")
    private String joinHint;

    @JsonProperty("source")
    private String source;

    /**
     * JSON 顶层包装：{"foreign_keys":[...], "meta":{...}}
     * 只取 foreign_keys 数组，其余字段（meta / counts / caveats 等）由 Jackson 忽略。
     * 与 Exemplar.ExemplarFile、Glossary 用法一致。
     */
    @Data
    public static class ForeignKeyFile {
        @JsonProperty("foreign_keys")
        private List<ForeignKey> foreignKeys;
    }
}
