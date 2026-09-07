package com.springai.springai.text2sql;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 业务口径（metric_definition）数据载体。
 *
 * 【为什么需要这个类】
 * AI 生成 SQL 容易出错的不是语法，是"口径"：
 *   - 用户说"设备数量"，到底算 `COUNT(*)` 还是 `COUNT(DISTINCT device.id)`？
 *   - 用户说"停机时长"，是否排除未结束记录？按秒还是按分钟？
 *   - 用户说"OEE"，是取最新一条还是平均值？按班次还是按天？
 * 把口径显式结构化，AI 拼 Prompt 时才能稳定拿到"这是怎么算的"。
 *
 * 【字段说明】
 *   metric        指标中文名（如"设备数量"）
 *   synonyms      同义词（如["机器数","设备数"]），方便 AI 召回
 *   definition    口径文字描述（直接喂给 Prompt）
 *   expression    标准 SQL 表达式片段（喂给 Prompt 也行，业务方对账也能用）
 *   grain         度量粒度（如 device / shift / day）
 *   tables        涉及表（list）
 *   excludedCond  排除条件（如 "deleted = false"），帮助 AI 加 WHERE 过滤
 *   source        口径来源（"设备管理口径确认"），用于审计
 *   confidence    置信度：high / medium / low（low 表示需人工复核）
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetricDefinition {

    private String metric;
    private List<String> synonyms;
    private String definition;
    private String expression;
    private String grain;
    private List<String> tables;

    @JsonProperty("excluded_conditions")
    private List<String> excludedCond;

    private String source;
    private String confidence;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MetricFile {
        @JsonProperty("metrics")
        private List<MetricDefinition> metrics;
    }
}