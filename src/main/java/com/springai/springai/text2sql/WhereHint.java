package com.springai.springai.text2sql;

import lombok.Data;

import java.util.List;

/**
 * where_hint 向量类型的 DTO（对应 scripts/validate/where_hints.json）。
 *
 * 【它解决什么】
 * 从 IMS 的 MyBatis Mapper XML 里确定性抽取「每张表常用的 WHERE 过滤字段 + 运算 + 频次 + 列注释」，
 * 向量化入库（type=where_hint）。提问时按 schema 召回出的表名精确拉取，注入 Prompt，
 * 把"源码真实查询模式"作为 LLM 生成 WHERE 的参考先验，缓解两类问题：
 *   1) 列选错（如 device 表高频过滤字段是 device_name 而非 device_code）
 *   2) 时间相对化（如 device_operation_report 时间一律用 start_time/end_time 区间）
 *
 * 【与 glossary 的关系】
 * glossary 负责"业务语义 → 字段"的消歧（device_name 是设备名），
 * where_hint 负责"这张表历史上常用哪些字段过滤"的频次先验。
 * 二者是互补信号：where_hint 偏统计先验，glossary 偏语义权威；都以 hint 形式注入，非硬规则。
 */
public class WhereHint {

    @Data
    public static class WhereHintFile {
        private List<WhereHintItem> items;
    }

    @Data
    public static class WhereHintItem {
        private String table;
        private String tableComment;
        private List<String> sourceFiles;
        private List<WhereColumn> columns;
        private boolean dateRangeHint;
        private List<String> dateColumns;
        /** 直接可注入 Prompt 的渲染文本（由抽取脚本生成，Java 端原样使用，保证单一真源） */
        private String content;
    }

    @Data
    public static class WhereColumn {
        private String table;
        private String column;
        private List<String> ops;
        private int freq;
        private String comment;
    }
}
