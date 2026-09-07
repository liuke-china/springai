package com.springai.springai.text2sql;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 查询计划（Query Plan）—— 让 AI 在生成 SQL 前先写一份结构化计划。
 *
 * 【为什么需要】
 * 直接让 LLM 写 SQL 经常"语法对、业务错"：
 *   - 漏维度（用户要按项目分，AI 没加 GROUP BY）
 *   - 错时间范围（"最近一个月"被理解成 7 天）
 *   - 错指标口径（"设备数量"用了 COUNT(*) 而不是 COUNT(DISTINCT)）
 *   - 错 JOIN 顺序
 * 把这些维度显式列出来，让 AI 一次只填一个字段，更稳；
 * 再把这个计划作为"事实档案"注入第二步 SQL 生成，能大幅降低错误率。
 *
 * 【字段解释（每个都对应面试官常问的"你怎么确保 SQL 答对问题"）】
 *   metric      要算什么（如"停机时长"）
 *   dimensions  按哪些维度分组（项目/工厂/班次）
 *   timeRange   时间范围（自然语言+已解析的起止）
 *   tables      涉及表（从 schema 召回里挑）
 *   joins       表关联表达式（从 foreign_key 召回里挑）
 *   filters     WHERE 过滤条件（业务词→字段映射）
 *   aggregation 聚合函数（SUM/COUNT/AVG）
 *   groupBy     是否需要 GROUP BY
 *   ambiguities 仍存在的歧义（如"最近一个月=自然月还是30天？"），让用户或后续追问
 *   confidence  0-1，对自身计划的把握度
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class QueryPlan {

    private String metric;
    private List<String> dimensions;
    private String timeRange;
    private List<String> tables;
    private List<String> joins;
    private List<String> filters;
    private String aggregation;
    private Boolean groupBy;
    private List<String> ambiguities;
    private Double confidence;
}