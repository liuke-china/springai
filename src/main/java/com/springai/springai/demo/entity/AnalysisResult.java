package com.springai.springai.smalldemo.entity;

import lombok.Data;

import java.util.List;

/**
 * 数据分析报告（嵌套结构）
 * 包含总结 + 多个分析维度 + 建议
 */
@Data
public class AnalysisResult {

    /**
     * 一句话总结
     */
    private String summary;

    /**
     * 各维度的分析结果
     */
    private List<DimensionResult> dimensions;

    /**
     * 改进建议
     */
    private String suggestion;

    @Data
    public static class DimensionResult {

        /**
         * 维度名称（如"总体趋势"、"异常点"）
         */
        private String name;

        /**
         * 该维度的分析内容
         */
        private String content;

        /**
         * 评分（0-100）
         */
        private Integer score;
    }
}
