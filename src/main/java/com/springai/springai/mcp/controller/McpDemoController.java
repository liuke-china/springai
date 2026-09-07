package com.springai.springai.mcp.controller;

import com.springai.springai.mcp.server.WeatherMcpTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 天气 Demo 控制器（测试入口）
 * =====================================================================
 * 提供 3 个 REST 接口，方便你直观看到 MCP 工具怎么工作：
 *
 *  ① GET /mcp/weather?q=深圳天气怎么样
 *     —— 让大模型根据用户问题，自动决定调用 getWeather / getTemperature 工具，
 *        并返回自然语言答案。控制台会打印 "[WeatherMcpTools] ▶ 调用 MCP 工具..."，
 *        证明工具确实被触发了。
 *
 *  ② GET /mcp/tools
 *     —— 直接列出当前已注册成 MCP 工具的方法名和描述（证明 MCP Server 注册成功）。
 *
 *  ③ GET /mcp/weather-raw?city=深圳
 *     —— 不走大模型，直接调用 getWeather 工具本体，返回原始结构化结果。
 *
 * 【为什么注入 WeatherMcpTools 而不是 MethodToolCallbackProvider？】
 *   Spring AI MCP Server 的自动配置（ToolCallbackConverterAutoConfiguration）会
 *   扫描所有 @Tool Bean 并自动创建 ToolCallbackProvider。如果 Controller 直接
 *   注入 MethodToolCallbackProvider，可能跟自动配置产生的 Bean 冲突或找不到。
 *   改为注入 WeatherMcpTools 本体（@Component，100% 能扫到），然后在方法里
 *   用 MethodToolCallbackProvider.builder().toolObjects(...).build() 动态创建，
 *   彻底绕开 Bean 冲突。
 * =====================================================================
 */
@RestController
@RequestMapping("/mcp")
public class McpDemoController {

    private final ChatClient chatClient;
    private final WeatherMcpTools weatherMcpTools;

    // 注意：Spring AI 1.1 只自动配置 ChatClient.Builder（不是 ChatClient 实例 Bean）。
    // 对齐项目其他 Controller 的写法：注入 Builder，在构造函数里 build()。
    public McpDemoController(ChatClient.Builder chatClientBuilder, WeatherMcpTools weatherMcpTools) {
        this.chatClient = chatClientBuilder.build();
        this.weatherMcpTools = weatherMcpTools;
    }

    /**
     * 动态创建天气工具的 ToolCallbackProvider（每次调用都新建，避免 Bean 冲突）。
     */
    private MethodToolCallbackProvider weatherProvider() {
        return MethodToolCallbackProvider.builder()
                .toolObjects(weatherMcpTools)
                .build();
    }

    /**
     * ① 自然语言问天气 —— 大模型自动决定是否、调用哪个工具。
     */
    @GetMapping("/weather")
    public String askWeather(@RequestParam("q") String question) {
        System.out.println("[McpDemoController] 收到天气提问: " + question);
        return chatClient.prompt()
                .user(question)
                .toolCallbacks(weatherProvider())   // 用 toolCallbacks() 收 ToolCallbackProvider（.tools() 只收含 @Tool 方法的对象本体）
                .call()
                .content();
    }

    /**
     * ② 列出当前已注册成 MCP 工具的方法（证明 MCP Server 注册成功）。
     */
    @GetMapping("/tools")
    public Map<String, Object> listTools() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, String>> tools = new ArrayList<>();
        for (ToolCallback cb : weatherProvider().getToolCallbacks()) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", cb.getToolDefinition().name());
            m.put("description", cb.getToolDefinition().description());
            tools.add(m);
        }
        result.put("mcpEndpoint", "http://localhost:8081/mcp");
        result.put("toolCount", tools.size());
        result.put("tools", tools);
        return result;
    }

    /**
     * ③ 直接调用工具本体（不走大模型），返回原始结构化结果。
     */
    @GetMapping("/weather-raw")
    public Object rawWeather(@RequestParam("city") String city) {
        // 直接调 WeatherMcpTools 的 mock 数据（不走 ToolCallback 协议）
        return weatherMcpTools.getWeather(city);
    }
}
