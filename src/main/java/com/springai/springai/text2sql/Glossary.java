package com.springai.springai.text2sql;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 业务术语 / 字段枚举字典 DTO。
 *
 * 对应 text2sql-refs/ims_glossary.json 结构。每条 = 一个业务术语（或枚举值含义）定义，
 * 向量化后 type='glossary' 入库，提问时按语义检索注入 Prompt，强制 AI 按真实业务含义理解问题。
 */
public class Glossary {

    @JsonProperty("glossary")
    private List<GlossaryItem> items;

    public List<GlossaryItem> getItems() { return items; }
    public void setItems(List<GlossaryItem> items) { this.items = items; }

    public static class GlossaryItem {

        @JsonProperty("term")
        private String term;

        @JsonProperty("definition")
        private String definition;

        @JsonProperty("tables")
        private String tables;

        @JsonProperty("fields")
        private String fields;

        @JsonProperty("synonyms")
        private String synonyms;

        public String getTerm() { return term; }
        public void setTerm(String term) { this.term = term; }

        public String getDefinition() { return definition; }
        public void setDefinition(String definition) { this.definition = definition; }

        public String getTables() { return tables; }
        public void setTables(String tables) { this.tables = tables; }

        public String getFields() { return fields; }
        public void setFields(String fields) { this.fields = fields; }

        public String getSynonyms() { return synonyms; }
        public void setSynonyms(String synonyms) { this.synonyms = synonyms; }
    }
}
