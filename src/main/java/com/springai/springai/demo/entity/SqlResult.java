package com.springai.springai.demo.entity;

import lombok.Data;

import java.util.List;

/**
 * Text-to-SQL 结构化输出结果
 *
 * 这是 Text-to-SQL 功能的核心 DTO，
 * 大模型生成的 SQL 不再是纯文本字符串，而是解析为 Java 对象
 *
 * 使用场景：项目经理问 AI 要数据 -> AI 返回 SqlResult -> 后端校验 SQL -> 执行 -> 导出 Excel
 */
@Data
public class SqlResult {

    /**
     * 生成的 SQL 语句
     */
    private String sql;

    /**
     * SQL 的中文解释（给项目经理看的）
     */
    private String explanation;

    /**
     * 涉及的数据库表名列表
     */
    private List<String> tables;

    /**
     * 风险等级
     * SAFE: 简单查询
     * WARNING: 多表JOIN、聚合、子查询
     * DANGEROUS: 全表扫描、大数据量
     */
    private String riskLevel;

    /**
     * 风险说明
     */
    private String riskNote;
}
