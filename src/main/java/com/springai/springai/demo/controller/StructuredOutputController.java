package com.springai.springai.smalldemo.controller;

import com.springai.springai.smalldemo.entity.AnalysisResult;
import com.springai.springai.smalldemo.entity.ExpenseInfo;
import com.springai.springai.smalldemo.entity.ExtractedEntity;
import com.springai.springai.smalldemo.entity.SqlResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 结构化输出控制器
 *
 * 【为什么需要结构化输出】
 * AI 默认返回纯文本字符串，但实际业务需要：
 * - Text-to-SQL：需要拿到 sql、explanation、tables 等字段
 * - 信息提取：从文本中提取姓名、日期、金额等结构化数据
 * - 分类场景：返回分类结果和置信度
 *
 * 【Spring AI 的三种方式】
 * 1. .entity(Class)           -> 返回单个 Java 对象
 * 2. .entity(new TypeRef<>()） -> 返回嵌套/集合类型
 * 3. .entities(Class)         -> 返回 List<Class>（多个对象）
 */
@RestController
@RequestMapping("/ai/structured")
public class StructuredOutputController {

    private final ChatClient chatClient;

    public StructuredOutputController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    // ==================== 1. 基础：单对象映射 ====================

    /**
     * 从自然语言中提取结构化信息
     * GET /ai/structured/extract?text=张三今天花了350元买了一台联想笔记本
     *
     * 输入自然语言，AI 提取为 ExpenseInfo 对象
     */
    @GetMapping("/extract")
    public ExpenseInfo extract(@RequestParam String text) {
        return chatClient.prompt()
            .system("你是一个信息提取专家，从用户提供的文本中提取消费信息。只返回JSON，不要其他文字。")
            .user(text)
            .call()
            .entity(ExpenseInfo.class);
    }

    // ==================== 2. 核心：Text-to-SQL 结构化输出 ====================

    /**
     * Text-to-SQL：根据表结构和需求生成 SQL（返回结构化对象）
     * POST /ai/structured/sql
     * Body: {"tableSchema":"表结构DDL","requirement":"查询需求"}
     *
     * 返回 SqlResult 对象，包含：
     * - sql: 生成的 SQL 语句
     * - explanation: SQL 的中文解释
     * - tables: 涉及的表名列表
     * - riskLevel: 风险等级（SAFE/WARNING/DANGEROUS）
     * - riskNote: 风险说明
     */
    @PostMapping("/sql")
    public SqlResult textToSql(@RequestBody Map<String, String> request) {
        String tableSchema = request.get("tableSchema");
        String requirement = request.get("requirement");

        String prompt = String.format("""
                你是 PostgreSQL 数据库专家。根据以下表结构和用户需求，生成 SQL 查询语句。

                【表结构】
                %s

                【用户需求】
                %s

                【返回格式】严格返回以下JSON格式：
                {
                  "sql": "SELECT * FROM ...",
                  "explanation": "这条SQL的作用说明",
                  "tables": ["table1", "table2"],
                  "riskLevel": "SAFE",
                  "riskNote": "风险说明，无风险则填'无'"
                }

                【规则】
                1. 只生成 SELECT 查询，禁止生成 DELETE/UPDATE/DROP/INSERT/TRUNCATE
                2. 必须加 LIMIT，默认最多返回1000条
                3. riskLevel 取值：SAFE（简单查询）/ WARNING（涉及多表JOIN或聚合）/ DANGEROUS（涉及全表扫描或大数据量）
                4. 如果需求无法用 SQL 实现，sql 字段返回空字符串，explanation 中说明原因
                """, tableSchema, requirement);

        return chatClient.prompt()
            .user(prompt)
            .call()
            .entity(SqlResult.class);
    }

    // ==================== 3. 进阶：嵌套对象输出 ====================

    /**
     * 生成数据分析报告（嵌套结构）
     * POST /ai/structured/analysis
     * Body: {"data":"原始数据","dimensions":"分析维度，逗号分隔"}
     *
     * 返回 AnalysisResult 对象，包含嵌套的 List<DimensionResult>
     */
    @PostMapping("/analysis")
    public AnalysisResult analysis(@RequestBody Map<String, String> request) {
        String data = request.get("data");
        String dimensions = request.getOrDefault("dimensions", "总体趋势,异常点,建议");

        String prompt = String.format("""
                你是数据分析专家。分析以下数据并生成结构化报告。

                【原始数据】
                %s

                【分析维度】%s

                【返回格式】严格返回以下JSON格式：
                {
                  "summary": "一句话总结",
                  "dimensions": [
                    {
                      "name": "维度名称",
                      "content": "该维度的分析内容",
                      "score": 85
                    }
                  ],
                  "suggestion": "改进建议"
                }
                """, data, dimensions);

        return chatClient.prompt()
            .user(prompt)
            .call()
            .entity(AnalysisResult.class);
    }

    // ==================== 4. 高级：返回 List 对象 ====================

    /**
     * 从文本中提取多个实体（返回列表）
     * GET /ai/structured/entities?text=张三和李四去北京出差，预算5000元，住3天酒店
     *
     * 返回 List<ExtractedEntity>，每个实体包含 type、value、description
     */
    @GetMapping("/entities")
    public List<ExtractedEntity> entities(@RequestParam String text) {
        return chatClient.prompt()
            .system("你是一个信息提取专家。从文本中提取所有有价值的实体信息。返回JSON数组，不要其他文字。")
            .user(text)
            .call()
            .entity(new ParameterizedTypeReference<List<ExtractedEntity>>() {});
    }

    // ==================== 5. 实战：批量 SQL 生成（List 结构化输出） ====================

    /**
     * 批量生成 SQL（返回 List<SqlResult>）
     * POST /ai/structured/sql-batch
     * Body: {"tableSchema":"表结构DDL","requirements":["需求1","需求2","需求3"]}
     *
     * 企业场景：项目经理一次提多个数据需求，AI 批量生成 SQL
     */
    @PostMapping("/sql-batch")
    public List<SqlResult> sqlBatch(@RequestBody Map<String, Object> request) {
        String tableSchema = (String) request.get("tableSchema");
        @SuppressWarnings("unchecked")
        List<String> requirements = (List<String>) request.get("requirements");

        StringBuilder reqBuilder = new StringBuilder();
        for (int i = 0; i < requirements.size(); i++) {
            reqBuilder.append(String.format("%d. %s\n", i + 1, requirements.get(i)));
        }

        String prompt = String.format("""
                你是 PostgreSQL 数据库专家。根据以下表结构，为每个需求生成 SQL。

                【表结构】
                %s

                【需求列表】
                %s

                【返回格式】严格返回JSON数组，每个元素格式：
                {
                  "sql": "SELECT ...",
                  "explanation": "说明",
                  "tables": ["表名"],
                  "riskLevel": "SAFE",
                  "riskNote": "无"
                }

                【规则】
                1. 只生成 SELECT，加 LIMIT 1000
                2. 数组顺序与需求列表一一对应
                3. 无法实现的 SQL，sql 字段留空，explanation 说明原因
                """, tableSchema, reqBuilder);

        return chatClient.prompt()
            .user(prompt)
            .call()
            .entity(new ParameterizedTypeReference<List<SqlResult>>() {});
    }
}
