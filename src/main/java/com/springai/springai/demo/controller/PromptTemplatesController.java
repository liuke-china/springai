package com.springai.springai.demo.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * Prompt Templates 演示控制器
 *
 * 【核心概念】
 * PromptTemplate：将提示词模板化，支持占位符替换，方便复用
 * - 占位符格式：{参数名} 或 {参数名,默认值}
 * - 支持 List 类型参数自动循环展开
 *
 * 【与普通字符串的区别】
 * 普通字符串：String prompt = "天气如何";
 * 模板字符串：String prompt = "请告诉我{city}的天气";
 *
 * 【Postman 对应】
 * POSTMAN：demo.postman_collection.json
 * 分组：「2. 提示词模板」→ basic / multi / default / list / system / stream / bean / summary / email-bean / if 共 10 个接口
 *      1-6 是"每次新建模板"的用法，7-9（bean/summary/email-bean）是"注入 @Bean 预定义模板"的推荐用法
 */
@RestController
@RequestMapping("/ai/template")
public class PromptTemplatesController {

    private final ChatClient chatClient;
    private final PromptTemplate translatorTemplate;  // 注入翻译模板
    private final PromptTemplate summarizerTemplate;   // 注入摘要模板
    private final PromptTemplate emailTemplate;        // 注入邮件模板

    public PromptTemplatesController(
            ChatClient.Builder chatClientBuilder,
            PromptTemplate translatorTemplate,
            PromptTemplate summarizerTemplate,
            PromptTemplate emailTemplate) {
        this.chatClient = chatClientBuilder.build();
        this.translatorTemplate = translatorTemplate;
        this.summarizerTemplate = summarizerTemplate;
        this.emailTemplate = emailTemplate;
    }

    // ==================== 1. 基础模板：单参数替换 ====================
    /**
     * 演示：最基础的模板用法——{参数名} 占位符替换
     * Postman：分组「2. 提示词模板」→ basic
     * 示例请求：GET /ai/template/basic?city=北京
     *
     * 流程：模板 "{city}的天气" → create(Map.of("city", city)) 替换占位符 → 发给 AI
     * 场景：所有模板的起点，先看懂这个再看后面的多参数/Bean 版本
     */
    @GetMapping("/basic")
    public String basicTemplate(@RequestParam(defaultValue = "北京") String city) {
        // 定义模板，{city} 是占位符
        PromptTemplate template = new PromptTemplate("请告诉我 {city} 的天气情况，用简洁的话回答。");

        // 创建 Prompt，传入参数 Map
        Prompt prompt = template.create(Map.of("city", city));

        // 调用 AI
        return chatClient.prompt(prompt)
                .call()
                .content();
    }

    // ==================== 2. 多参数模板 ====================
    /**
     * 演示：一个模板里放多个占位符，一次 Map 全部替换
     * Postman：分组「2. 提示词模板」→ multi
     * 示例请求：GET /ai/template/multi?name=小明&languageType=中文&language=今天天气真好
     *
     * 关键点：Map 的 key 必须和占位符名一一对应，少传会抛错
     */
    @GetMapping("/multi")
    public String multiParams(@RequestParam(defaultValue = "小明") String name,
                              @RequestParam(defaultValue = "中文") String languageType,
                              @RequestParam(defaultValue = "今天天气真好") String language) {
        PromptTemplate template = new PromptTemplate(
                "你是一个翻译官，用户名叫 {name}。" +
                "请把以下句子翻译成 {languageType}：{language}！"
        );

        Prompt prompt = template.create(Map.of(
                "name", name,
                "languageType", languageType,
                "language", language
        ));

        return chatClient.prompt(prompt)
                .call()
                .content();
    }

    // ==================== 3. 条件默认值（手动处理） ====================
    /**
     * 演示：参数缺省时用程序逻辑给默认值（比模板自带默认值语法更稳）
     * Postman：分组「2. 提示词模板」→ default
     * 示例请求：GET /ai/template/default（不传 style → "友好的"）
     *          GET /ai/template/default?style=严肃
     *
     * 场景：对外接口参数可省略时的标准处理方式
     */
    @GetMapping("/default")
    public String withDefaultValue(@RequestParam(required = false) String style) {
        // 手动处理默认值，不用模板的默认值语法
        String styleText = (style == null || style.isEmpty()) ? "友好的" : style;

        PromptTemplate template = new PromptTemplate(
                "请用" + styleText + "语气自我介绍"
        );

        Prompt prompt = template.create();

        return chatClient.prompt(prompt)
                .call()
                .content();
    }

    // ==================== 4. List 参数（循环展开） ====================
    /**
     * 演示：List 类型参数自动循环展开成"苹果、香蕉、橙子"
     * Postman：分组「2. 提示词模板」→ list
     * 示例请求：GET /ai/template/list?items=苹果,香蕉,橙子
     *
     * 关键点：逗号分隔的入参先 split 成 List 再传给模板；同时演示模板 + 流式输出组合
     */
    @GetMapping("/list")
    public Flux<String> listParam(@RequestParam(defaultValue = "苹果,香蕉,橙子") String items) {
        PromptTemplate template = new PromptTemplate(
                "请列出以下水果的详情：{items}，每个水果最少100字"
        );

        // List 会被自动展开为 "苹果、香蕉、橙子"
        Prompt prompt = template.create(Map.of("items", List.of(items.split(","))));

        return chatClient.prompt()
                .user(prompt.getContents())
                .stream()
                .content();
    }

    // ==================== 5. 系统模板（SystemPromptTemplate） ====================
    /**
     * 演示：SystemPromptTemplate 与 PromptTemplate 分离——系统人设和用户输入各管各的
     * Postman：分组「2. 提示词模板」→ system
     * 示例请求：GET /ai/template/system?job=律师&language=英文
     *
     * 流程：system 模板（角色）+ user 模板（问题）→ 组装成一个 Prompt → 调 AI
     * 场景：人设固定、问题多变的业务（客服/翻译）都用这种分离写法
     */
    @GetMapping("/system")
    public String systemTemplate(@RequestParam(defaultValue = "律师") String job,
                                 @RequestParam(defaultValue = "英文") String language) {
        // 系统角色模板
        SystemPromptTemplate systemTemplate = new SystemPromptTemplate(
                "你是一个专业的{job}，只使用{language}进行回复。"
        );

        // 用户消息模板
        PromptTemplate userTemplate = new PromptTemplate("请解释什么是合同？");

        // 组合成完整 Prompt
        Prompt prompt = new Prompt(
                systemTemplate.createMessage(Map.of("job", job, "language", language)),
                userTemplate.createMessage()
        );

        return chatClient.prompt(prompt)
                .call()
                .content();
    }

    // ==================== 6. 模板 + 流式输出 ====================
    /**
     * 演示：模板 + 流式输出组合（模板生成 prompt 后走 stream）
     * Postman：分组「2. 提示词模板」→ stream
     * 示例请求：GET /ai/template/stream?topic=Java
     *
     * 注意：返回 Flux<String>，produces 必须是 text/event-stream
     */
    @GetMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> streamTemplate(@RequestParam(defaultValue = "Java") String topic) {
        PromptTemplate template = new PromptTemplate(
                "用 5 句话介绍一下 {topic} 编程语言"
        );

        Prompt prompt = template.create(Map.of("topic", topic));

        return chatClient.prompt(prompt)
                .stream()
                .content();
    }

    // ==================== 7. 预定义模板 Bean（推荐方式） ====================
    /**
     * 演示：注入 @Bean 预定义模板 translatorTemplate（企业推荐方式）
     * Postman：分组「2. 提示词模板」→ bean
     * 示例请求：GET /ai/template/bean?text=Hello&language=中文
     *
     * 优点：模板集中管理（改提示词不用动 Controller 代码）、启动时加载
     * 场景：模板来源是 PromptTemplateConfig 里的 @Bean，与前面"每次 new"形成对照
     */
    @GetMapping("/bean")
    public String beanTemplate(
            @RequestParam(defaultValue = "Hello") String text,
            @RequestParam(defaultValue = "中文") String language) {

        // 使用注入的 Bean
        Prompt prompt = translatorTemplate.create(Map.of(
                "targetLanguage", language,
                "text", text
        ));

        return chatClient.prompt(prompt)
                .call()
                .content();
    }

    // ==================== 8. Bean 模板：摘要生成 ====================
    /**
     * 演示：注入 summarizerTemplate Bean 做摘要生成
     * Postman：分组「2. 提示词模板」→ summary
     * 示例请求：GET /ai/template/summary?length=30
     *          （content 不传有默认长文，length 控制摘要字数）
     *
     * 用途：长文本压缩摘要，模板里 {length} 和 {content} 双占位符
     */
    @GetMapping("/summary")
    public String summaryTemplate(
            @RequestParam(defaultValue = "Spring AI 是 Spring 生态系统中用于 AI 集成的框架。它提供了与各种 AI 模型交互的统一抽象。核心设计原则是可移植性和模块化，使得开发者可以在不同的 AI 提供商之间切换，而无需修改业务代码。") String content,
            @RequestParam(defaultValue = "30") int length) {

        Prompt prompt = summarizerTemplate.create(Map.of(
                "length", length,
                "content", content
        ));

        return chatClient.prompt(prompt)
                .call()
                .content();
    }

    // ==================== 9. Bean 模板：邮件生成 ====================
    /**
     * 演示：注入 emailTemplate Bean 生成商务邮件（4 个占位符）
     * Postman：分组「2. 提示词模板」→ email-bean
     * 示例请求：GET /ai/template/email-bean?to=张三&subject=项目汇报&points=本周完成XXX&tone=正式
     *
     * 场景：工作周报/通知邮件自动生成，4 个参数全部可省略走默认值
     */
    @GetMapping("/email-bean")
    public String emailBeanTemplate(
            @RequestParam(defaultValue = "张三") String to,
            @RequestParam(defaultValue = "项目进度汇报") String subject,
            @RequestParam(defaultValue = "1. 本周完成了登录模块 2. 下周开始订单模块") String points,
            @RequestParam(defaultValue = "正式") String tone) {

        Prompt prompt = emailTemplate.create(Map.of(
                "to", to,
                "subject", subject,
                "points", points,
                "tone", tone
        ));

        return chatClient.prompt(prompt)
                .call()
                .content();
    }

    // ==================== 10. 条件逻辑模板（IF 语句） ====================
    /**
     * 演示：模板里用 #if/#else/#end 做条件分支（Velocity 语法）
     * Postman：分组「2. 提示词模板」→ if
     * 示例请求：GET /ai/template/if?mood=我操（Postman 里填的这个，走 #else 安抚分支）
     *          GET /ai/template/if?mood=happy（走 #if 鼓励分支）
     *
     * 关键点：条件逻辑在模板层完成，不同参数生成不同 prompt，不用写 Java if-else
     */
    @GetMapping("/if")
    public String ifCondition(@RequestParam(defaultValue = "很好") String mood) {
        String templateText = """
            用户当前心情：{mood}
            #if ($mood == 'happy')
            请回复一个鼓励性的好消息！
            #else
            请回复一个安慰和鼓励的话。
            #end
            """;

        PromptTemplate template = new PromptTemplate(templateText);

        Prompt prompt = template.create(Map.of("mood", mood));

        return chatClient.prompt(prompt)
                .call()
                .content();
    }
}
