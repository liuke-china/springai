package com.springai.springai.demo.controller;

import com.springai.springai.demo.entity.AnalysisResult;
import com.springai.springai.demo.entity.ExpenseInfo;
import com.springai.springai.demo.entity.ExtractedEntity;
import com.springai.springai.demo.entity.SqlResult;
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
 *
 * 【Postman 对应】
 * 集合：Spring AI Full API.postman_collection.json（桌面）
 * 分组：「7. Structured Output」→ extract / sql / analysis / sql-batch 共 4 个接口
 * （/ai/structured/entities 本类有实现，Postman 集合里没有对应条目）
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
     * 演示：自然语言 → Java 对象（.entity(Class) 最基础用法）
     * Postman：分组「7. Structured Output」→ extract
     * 示例请求：GET /ai/structured/extract?text=张三购买MAC笔记本花费9999元（Postman 里的写法）
     *
     * 流程：system 要求只返回 JSON → user 文本 → entity(ExpenseInfo.class) 自动反序列化
     * 返回：ExpenseInfo（姓名/金额/商品等消费字段），可直接当 Java 对象用
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
     * 演示：Text-to-SQL 结构化输出——单表简单查询，看 riskLevel 会给 SAFE
     * Postman：分组「7. Structured Output」→ sql
     * 示例请求：POST /ai/structured/sql
     *          Body: {"tableSchema":"CREATE TABLE device (id BIGINT PRIMARY KEY, device_code VARCHAR(64), status SMALLINT);",
     *                 "requirement":"查询所有运行中(status=1)的设备"}
     *
     * 流程：表结构 DDL + 需求拼进 Prompt → 要求严格返回 5 字段 JSON → entity(SqlResult.class) 反序列化
     * 返回 SqlResult：sql / explanation / tables / riskLevel（SAFE/WARNING/DANGEROUS）/ riskNote
     * 关键点：Prompt 硬性规则"只生成 SELECT + 强制 LIMIT"，这是结构化输出承载业务约束的例子
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
     * 演示：嵌套对象结构化输出（对象里套 List<DimensionResult>）
     * Postman：分组「7. Structured Output」→ analysis
     * 示例请求：POST /ai/structured/analysis
     *          Body: {"data":"1号车间稼动率82%；2号车间91%；3号车间47%（8月12-15日停机4天）",
     *                 "dimensions":"总体趋势,异常车间,停机影响,改进建议"}
     *
     * 关键点：嵌套结构要用 ParameterizedTypeReference / 嵌套 DTO 承接，数据里埋异常点能看出 score 评分是真的
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
     * 演示：返回 JSON 数组 → List<ExtractedEntity>（泛型集合反序列化）
     * 示例请求：GET /ai/structured/entities?text=张三和李四去北京出差，预算5000元，住3天酒店
     *
     * 关键点：List 泛型必须用 ParameterizedTypeReference 承接，直接 List.class 会丢泛型
     * 注意：Postman 集合「7. Structured Output」分组没有这个条目，需手动新建 GET 请求测
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
     * 演示：批量 SQL 生成——List<SqlResult> 结构化输出（一次需求列表出多条 SQL）
     * Postman：分组「7. Structured Output」→ sql-batch
     * 示例请求：POST /ai/structured/sql-batch
     *          Body: {"tableSchema":"CREATE TABLE device (...); CREATE TABLE device_operation_report (...);",
     *                 "requirements":["查询运行中设备","统计每车间设备数","8月产量Top10"]}
     *
     * 流程：requirements 逐条编号拼进 Prompt → 要求返回 JSON 数组 → List<SqlResult> 反序列化
     * 关键点：数组顺序与需求列表一一对应；测试时三个需求按"单表→聚合→JOIN"递进，riskLevel 会从 SAFE 到 WARNING
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
