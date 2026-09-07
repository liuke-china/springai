package com.springai.springai.smalldemo.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;


/**
 * Spring AI Alibaba 演示控制器
 * 包含：ChatClient 最常用方法演示
 */
@RestController
public class BasicAiController {

    private final ChatClient chatClient;

    public BasicAiController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    // ==================== 1. 同步单次对话 ====================
    /**
     * 功能：发送用户消息，等待完整回复后一次性返回
     * 入参差异：
     *   - 只传 user()：纯用户输入
     *   - 同时传 system() + user()：带系统角色的人设对话
     */
    @GetMapping("/ai/basic")
    public String basicChat(@RequestParam String prompt) {
        return chatClient.prompt()
                .user(prompt)          // 用户输入（必需）
                .call()                // 同步调用（阻塞等待完整结果）
                .content();            // 直接提取字符串内容（最简返回）
    }

    // ==================== 2. 流式输出（高频场景） ====================
    /**
     * 功能：逐 token 流式返回，适合实时交互场景
     * 关键点：
     *   - 必须设置 produces = "text/event-stream"
     *   - 返回 Flux<String> 而非 String
     *   - 前端需用 EventSource 接收
     */
    @GetMapping(value = "/ai/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> streamChat(@RequestParam String prompt) {
        return chatClient.prompt()
                .user(prompt)
                .stream()              // 流式调用（非阻塞，逐 token 返回）
                .content();            // 提取每个响应块的文本内容
    }

    // ==================== 3. 获取完整响应对象 ====================
    /**
     * 功能：获取包含元数据的完整 ChatResponse
     * 适用场景：
     *   - 需要 token 消耗统计
     *   - 需要原始模型返回结构
     *   - 调试时查看完整响应
     */
    @GetMapping("/ai/response")
    public ChatResponse fullResponse(@RequestParam String prompt) {
        return chatClient.prompt()
                .user(prompt)
                .call()
                .chatResponse();       // 返回完整响应对象（含 metadata/generations）
    }

    // ==================== 4. 系统角色定制 ====================
    /**
     * 功能：通过 system() 设置 AI 人设
     * 入参差异：
     *   - system() 在 user() 前：定义角色行为
     *   - 多个 user()：模拟多轮对话（需配合 ChatMemory）
     */
    @GetMapping("/ai/role")
    public String withRole(
            @RequestParam(defaultValue = "你是一个严谨的 Java 架构师") String system,
            @RequestParam String prompt) {
        return chatClient.prompt()
                .system(system)        // 系统角色指令（影响后续所有回复）
                .user(prompt)          // 用户当前问题
                .call()
                .content();
    }

    // ==================== 5. 结构化输出（实体映射） ====================
    /**
     * 功能：将 AI 回复直接转为 Java 对象
     * 注意：
     *   - 需 AI 返回严格 JSON 格式
     *   - 实体类需有无参构造和 setter
     *   - 复杂类型用 ParameterizedTypeReference
     */
    @GetMapping("/ai/entity")
    public WeatherInfo getWeather(@RequestParam(defaultValue = "北京") String city) {
        return chatClient.prompt()
                .user(u -> u.text("""
                        请严格以 JSON 格式返回{city}的天气信息，不要输出任何多余文字。
                        需要三个字段：city 为城市名称(字符串)、weather 为天气描述(字符串)、temperature 为整数温度。
                        """).param("city", city))
                .call()
                .entity(WeatherInfo.class);
    }

    // ==================== 6. 流式 + 系统角色组合 ====================
    /**
     * 功能：流式输出 + 人设定制
     * 典型场景：带角色的实时聊天机器人
     */
    @GetMapping(value = "/ai/stream/role", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> streamWithRole(
            @RequestParam(defaultValue = "你是实时翻译官") String system,
            @RequestParam String prompt) {

        return chatClient.prompt()
                .system(system)
                .user(prompt)
                .stream()
                .content();
    }


    // 1. 定义清晰的实体类（字段名全小写，无复杂嵌套）
    public static class WeatherInfo {
        private String city;      // 城市
        private String weather;   // 天气描述
        private int temperature;  // 温度

        // 必须有无参构造
        public WeatherInfo() {}

        // getter/setter（IDE自动生成）
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        public String getWeather() { return weather; }
        public void setWeather(String weather) { this.weather = weather; }
        public int getTemperature() { return temperature; }
        public void setTemperature(int temperature) { this.temperature = temperature; }
    }
}
