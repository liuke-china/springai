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
     * 1. SQL 生成
     * 测试：GET /ai/enterprise/sql
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
     * 2. 代码解释
     * 测试：GET /ai/enterprise/code-explain
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
     * 3. 数据分析报告
     * 测试：GET /ai/enterprise/analysis
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
     * 4. 客服自动回复
     * 测试：GET /ai/enterprise/customer
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
     * 5. 内容审核
     * 测试：GET /ai/enterprise/moderation
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
     * 6. 文本分类
     * 测试：GET /ai/enterprise/classify
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
     * 7. JSON 结构化输出
     * 测试：GET /ai/enterprise/json-extract
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
