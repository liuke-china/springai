package com.springai.springai.demo.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 企业常用模板示例控制器
 *
 * 【模板列表】
 * 1. SQL 生成 - 根据表结构生成 SQL
 * 2. 代码解释 - 解释代码功能
 * 3. 数据分析 - 生成数据分析报告
 * 4. 客服回复 - 自动回复客服问题
 * 5. 内容审核 - 审核内容是否违规
 * 6. 文本分类 - 文本归类
 * 7. JSON 提取 - 提取信息并输出 JSON
 *
 * 【Postman 对应】
 * POSTMAN：demo.postman_collection.json
 * 分组：「8. 提示词模板」→ sql / code-explain / analysis / customer / moderation / classify / json-extract 共 7 个接口
 * （7 个模板 Bean 全部定义在 PromptTemplateConfig，本类只负责注入和调用）
 */
@RestController
@RequestMapping("/ai/enterprise")
public class EnterpriseTemplatesController {

    private final ChatClient chatClient;
    private final PromptTemplate sqlGeneratorTemplate;
    private final PromptTemplate codeExplainerTemplate;
    private final PromptTemplate dataAnalysisTemplate;
    private final PromptTemplate customerServiceTemplate;
    private final PromptTemplate contentModerationTemplate;
    private final PromptTemplate textClassifierTemplate;
    private final PromptTemplate jsonOutputTemplate;

    public EnterpriseTemplatesController(
            ChatClient.Builder chatClientBuilder,
            PromptTemplate sqlGeneratorTemplate,
            PromptTemplate codeExplainerTemplate,
            PromptTemplate dataAnalysisTemplate,
            PromptTemplate customerServiceTemplate,
            PromptTemplate contentModerationTemplate,
            PromptTemplate textClassifierTemplate,
            PromptTemplate jsonOutputTemplate) {
        this.chatClient = chatClientBuilder.build();
        this.sqlGeneratorTemplate = sqlGeneratorTemplate;
        this.codeExplainerTemplate = codeExplainerTemplate;
        this.dataAnalysisTemplate = dataAnalysisTemplate;
        this.customerServiceTemplate = customerServiceTemplate;
        this.contentModerationTemplate = contentModerationTemplate;
        this.textClassifierTemplate = textClassifierTemplate;
        this.jsonOutputTemplate = jsonOutputTemplate;
    }

    /**
     * 1. SQL 生成：表结构 DDL + 需求 → 指定方言的 SQL
     * Postman：分组「8. 提示词模板」→ sql
     * 示例请求：GET /ai/enterprise/sql?question=查询用户表&dbType=PostgreSQL
     *          （tableSchema/requirement 不传走默认值；Postman 用的是 question 参数，实际生效的是默认 requirement）
     *
     * 场景：给 DBA 或后端用的 SQL 生成器，{dbType} 切换方言
     */
    @GetMapping("/sql")
    public String sqlGenerator(
            @RequestParam(defaultValue = "PostgreSQL") String dbType,
            @RequestParam(defaultValue = "CREATE TABLE users (id BIGINT, name VARCHAR(50), email VARCHAR(100))") String tableSchema,
            @RequestParam(defaultValue = "查询所有用户") String requirement) {

        Prompt prompt = sqlGeneratorTemplate.create(Map.of(
                "dbType", dbType,
                "tableSchema", tableSchema,
                "requirement", requirement
        ));

        return chatClient.prompt(prompt).call().content();
    }

    /**
     * 2. 代码解释：代码片段 → 功能讲解
     * Postman：分组「8. 提示词模板」→ code-explain
     * 示例请求：GET /ai/enterprise/code-explain?code=public void test(){}
     *          （code 不传走默认的用户仓库查询类示例）
     *
     * 用途：新人看老代码、Code Review 辅助；注意 code 参数含特殊字符时 URL 要编码
     */
    @GetMapping("/code-explain")
    public String codeExplainer(
            @RequestParam(defaultValue = "java") String language,
            @RequestParam(defaultValue = "public class UserService {\n    private final UserRepository repository;\n    \n    public UserService(UserRepository repository) {\n        this.repository = repository;\n    }\n    \n    public User findById(Long id) {\n        return repository.findById(id).orElse(null);\n    }\n}") String code) {

        Prompt prompt = codeExplainerTemplate.create(Map.of(
                "language", language,
                "code", code
        ));

        return chatClient.prompt(prompt).call().content();
    }

    /**
     * 3. 数据分析：原始数据 + 维度 → 结构化分析报告
     * Postman：分组「8. 提示词模板」→ analysis
     * 示例请求：GET /ai/enterprise/analysis?code=public class A{}
     *          （Postman 里误带了 code 参数，实际走默认 data/dimensions；正确参数是 data 和 dimensions）
     *
     * 关键点：{length} 控制报告字数；这是 GET 接口传长数据的反面示例——大数据量应改 POST
     */
    @GetMapping("/analysis")
    public String dataAnalysis(
            @RequestParam(defaultValue = "本月销售额：100万，上月：80万，增长25%；新客户：50人，流失：10人") String data,
            @RequestParam(defaultValue = "销售趋势、客户留存") String dimensions,
            @RequestParam(defaultValue = "200") int length) {

        Prompt prompt = dataAnalysisTemplate.create(Map.of(
                "data", data,
                "dimensions", dimensions,
                "length", length
        ));

        return chatClient.prompt(prompt).call().content();
    }

    /**
     * 4. 客服回复：公司 + 产品信息 + 用户问题 → 客服话术
     * Postman：分组「8. 提示词模板」→ customer
     * 示例请求：GET /ai/enterprise/customer?question=怎么退货
     *          （其余参数不传走默认值；{context} 可传上一轮对话做多轮客服）
     *
     * 场景：智能客服原型，productInfo 决定 AI 回答的知识边界
     */
    @GetMapping("/customer")
    public String customerService(
            @RequestParam(defaultValue = "某某科技") String company,
            @RequestParam(defaultValue = "小智") String botName,
            @RequestParam(defaultValue = "产品名称：智能客服系统\n功能：自动回复、工单管理、数据统计") String productInfo,
            @RequestParam(defaultValue = "你们的产品怎么收费的？") String question,
            @RequestParam(defaultValue = "无") String context) {

        Prompt prompt = customerServiceTemplate.create(Map.of(
                "company", company,
                "botName", botName,
                "productInfo", productInfo,
                "question", question,
                "context", context
        ));

        return chatClient.prompt(prompt).call().content();
    }

    /**
     * 5. 内容审核：判断内容是否违规
     * Postman：分组「8. 提示词模板」→ moderation
     * 示例请求：GET /ai/enterprise/moderation?text=这是一个测试
     *
     * 用途：UGC 发布前的合规拦截（评论区/帖子），正常文本返回通过，违禁内容返回违规原因
     */
    @GetMapping("/moderation")
    public String contentModeration(
            @RequestParam(defaultValue = "这是一个很好的产品，推荐大家购买！") String content) {

        Prompt prompt = contentModerationTemplate.create(Map.of(
                "content", content
        ));
        return chatClient.prompt(prompt).call().content();
    }

    /**
     * 6. 文本分类：把文本归到指定类别列表中的一个
     * Postman：分组「8. 提示词模板」→ classify
     * 示例请求：GET /ai/enterprise/classify?text=Samsung手机&categories=手机,电脑,服装
     *
     * 场景：工单自动分派、内容打标；{categories} 动态传入，同一模板适配不同业务分类体系
     */
    @GetMapping("/classify")
    public String textClassifier(
            @RequestParam(defaultValue = "今天大盘上涨2%，科技股表现强劲") String content,
            @RequestParam(defaultValue = "财经,体育,娱乐,科技,社会") String categories) {

        Prompt prompt = textClassifierTemplate.create(Map.of(
                "content", content,
                "categories", categories
        ));

        return chatClient.prompt(prompt).call().content();
    }

    /**
     * 7. JSON 提取：自由文本 → JSON 字符串
     * Postman：分组「8. 提示词模板」→ json-extract
     * 示例请求：GET /ai/enterprise/json-extract?text=苹果5个橘子3个
     *          （默认值示例含姓名/电话/邮箱，是信息抽取的典型测试数据）
     *
     * 关键点：返回的是 JSON 字符串（String）而非 Java 对象，与 StructuredOutputController 的 entity() 形成对照
     */
    @GetMapping("/json-extract")
    public String jsonOutput(
            @RequestParam(defaultValue = "我叫张三，今年28岁，电话是13812345678，邮箱是 zhangsan@email.com") String content) {

        Prompt prompt = jsonOutputTemplate.create(Map.of(
                "content", content
        ));

        return chatClient.prompt(prompt).call().content();
    }
}
