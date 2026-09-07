package com.springai.springai.demo.controller;

import com.springai.springai.demo.tools.DateTimeTools;
import com.springai.springai.demo.tools.EmailTools;

import com.springai.springai.demo.tools.WeatherTools;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spring AI Function Calling（工具调用）演示控制器
 *
 * 核心概念：
 *   AI 模型本身无法获取实时数据（如时间、天气、数据库等），
 *   通过 Function Calling 可以让 AI 在需要时自动调用 Java 方法获取数据，
 *   然后基于返回结果生成自然语言回答。
 *
 * 流程：
 *   1. 用户提问 → 2. AI 判断是否需要调用工具 → 3. AI 生成工具调用请求
 *   → 4. Spring AI 自动执行工具方法 → 5. 将结果返回给 AI → 6. AI 生成最终回答
 *
 * 【Postman 对应】
 * 集合：Spring AI Full API.postman_collection.json（桌面）
 * 分组：「3. AI工具」→ time / weather / multi / role / email 共 5 个接口，顺序与本类编号一致
 */
@RestController
public class AiToolController {

    private final ChatClient chatClient;
    private final EmailTools emailTools;

    public AiToolController(ChatModel chatModel,
                            EmailTools emailTools) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.emailTools = emailTools;
    }


    // ==================== 1. 单工具调用：获取当前时间 ====================
    // Postman：分组「3. AI工具」→ time

    /**
     * 演示：AI 自动调用 DateTimeTools 获取当前时间
     * 示例请求：GET /ai/tool/time?prompt=现在几点了
     *
     * 流程：用户问时间 → AI 发现没有实时数据 → 调用 getCurrentDateTime() → 基于结果回答
     */
    @GetMapping("/ai/tool/time")
    public String toolTime(@RequestParam(defaultValue = "现在几点了？今天星期几？") String prompt) {
        return chatClient.prompt()
                .user(prompt)
                /*
                 *  注释掉 .tools(...) 时返回：
                 *     抱歉，我无法获取当前的实时时间，因为我是一个AI助手，没有内置的实时时钟功能。
                 *  打开返回：
                 *     现在是 **2026年9月5日（星期六）14:38**，时区为 Asia/Shanghai（北京时间）。
                 */
                .tools(new DateTimeTools())
                .call()
                .content();
    }

    // ==================== 2. 单工具调用：查询天气 ====================
    // Postman：分组「3. AI工具」→ weather

    /**
     * 演示：AI 自动调用 WeatherTools 查询天气
     * 示例请求：GET /ai/tool/weather?prompt=北京天气怎么样
     *
     * 流程：用户问天气 → AI 调用 getWeather("北京") → 基于结果回答
     */
    @GetMapping("/ai/tool/weather")
    public String toolWeather(@RequestParam(defaultValue = "北京今天天气怎么样？") String prompt) {
        return chatClient.prompt()
                .user(prompt)
                /*
                 * 注意，这个天气是代码自己写的。 AI不会管工具做了什么，只会根据
                 * @Tool(description = "查询指定城市的天气信息，包括天气状况和温度。当用户询问某城市天气时使用此工具。")
                 * 发现这个问题需要调用这个工具，拿到return的String
                 */
                .tools(new WeatherTools())
                .call()
                .content();
    }

    // ==================== 3. 多工具组合调用 ====================
    // Postman：分组「3. AI工具」→ multi

    /**
     * 演示：AI 同时使用多个工具完成复杂任务
     * 示例请求：GET /ai/tool/multi?prompt=明天北京天气怎么样，帮我设个明天早上的闹钟
     *
     * 流程：用户问多问题 → AI 先获取时间 → 再查天气 → 再设闹钟 → 综合回答
     */
    @GetMapping("/ai/tool/multi")
    public String toolMulti(@RequestParam(defaultValue = "帮我查一下北京现在的天气，然后设一个明天早上8点的闹钟") String prompt) {
        return chatClient.prompt()
                .user(prompt)
                .tools(new DateTimeTools(), new WeatherTools())
                .call()
                .content();
    }

    // ==================== 4. 带系统角色的工具调用 ====================
    // Postman：分组「3. AI工具」→ role

    /**
     * 演示：系统角色 + 工具调用组合
     * 示例请求：GET /ai/tool/role?prompt=上海天气如何
     *
     * 场景：让 AI 以特定角色身份使用工具
     */
    @GetMapping("/ai/tool/role")
    public String toolWithRole(
            @RequestParam(defaultValue = "上海天气怎么样") String prompt,
            @RequestParam(defaultValue = "你是一个专业的天气播报员，用简洁专业的语气播报天气") String system) {
        return chatClient.prompt()
                .system(system)
                .user(prompt)
                .tools(new WeatherTools())
                .call()
                .content();
    }


    // ==================== 5. 外部工具：发送邮件 ====================
    // Postman：分组「3. AI工具」→ email（Postman 里收件人用的 2369860456@qq.com）

    /**
     * 演示：AI 调用邮件服务
     * 示例请求：GET /ai/tool/email?prompt=给zhangsan@example.com发邮件，主题是订单确认，内容是您的订单已发货
     *
     * 用途：订单通知、系统告警、营销邮件等
     * 注意：需在 application.yml 配 spring.mail.host/port/username/password
     */
    @GetMapping("/ai/tool/email")
    public String toolEmail(@RequestParam(defaultValue = "给用户zhangsan@example.com发邮件，主题是订单确认，内容是您的订单已发货") String prompt) {
        return chatClient.prompt()
                .user(prompt)
                .tools(emailTools)
                .call()
                .content();
    }


}
