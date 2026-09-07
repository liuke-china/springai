package com.springai.springai.mcp.config;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * MCP Server 的 Resources（资源）与 Prompts（提示模板）注册。
 * =====================================================================
 * 小节②核心：Spring AI 1.1.4 没有 @McpResource / @McpPrompt 注解，
 * Resources 和 Prompts 只能【编程式】注册。
 *
 * 正确姿势（和 Tools 一个套路）：
 *   - Tools  → 暴露 MethodToolCallbackProvider Bean（你已会）
 *   - Resources / Prompts → 暴露 SyncResourceSpecification / SyncPromptSpecification Bean
 *   Spring AI 的 McpServerAutoConfiguration 会【自动收集】这两类 Bean 并注册到 /mcp。
 *   （注意：McpSyncServerCustomizer 只接受【单个】，auto-config 自己已占一个，
 *    所以不要再自己写 customizer，否则启动报"找到 2 个 customizer"冲突。）
 *
 * 三大原语区别（面试常问）：
 *   Tools（工具）     —— LLM 主动调用、有副作用（查库/写数据）        ← 你已会（@Tool）
 *   Resources（资源） —— 只读背景资料，客户端"拉取"喂给 LLM，无副作用  ← 本类补
 *   Prompts（提示）   —— 预定义提示词模板，客户端"一键触发"，无副作用  ← 本类补
 * =====================================================================
 */
@Configuration
public class WeatherMcpResourcesPromptsConfig {

    /**
     * Resource（只读背景）：元数据 + 读处理器。
     * 用 @Bean 暴露成 SyncResourceSpecification，auto-config 自动收集注册。
     */
    @Bean
    public McpServerFeatures.SyncResourceSpecification weatherInfoResource() {
        McpSchema.Resource infoResource = McpSchema.Resource.builder()
                .uri("weather://server/info")
                .name("weather-server-info")
                .description("天气 MCP Server 的元信息：版本、支持城市、可用工具（只读背景资料）")
                .mimeType("text/plain")
                .build();

        return new McpServerFeatures.SyncResourceSpecification(
                infoResource,
                (McpSyncServerExchange exchange, McpSchema.ReadResourceRequest request) -> {
                    String uri = request.uri();
                    String text = """
                            天气 MCP Server v1.0.0
                            支持城市: 深圳 / 北京 / 上海 / 广州 / 哈尔滨
                            可用工具: getWeather(天气概况) / getTemperature(仅温度)
                            """;
                    McpSchema.TextResourceContents content =
                            new McpSchema.TextResourceContents(uri, "text/plain", text);
                    return new McpSchema.ReadResourceResult(List.of(content));
                });
    }

    /**
     * Prompt（预定义提示模板）：参数声明 + 处理器。
     * 用 @Bean 暴露成 SyncPromptSpecification，auto-config 自动收集注册。
     */
    @Bean
    public McpServerFeatures.SyncPromptSpecification weatherQueryPrompt() {
        McpSchema.PromptArgument cityArg =
                new McpSchema.PromptArgument("city", "要查询天气的城市中文名，如 深圳", true);
        McpSchema.Prompt weatherPrompt = new McpSchema.Prompt(
                "weather-query", "生成一句询问某城市天气的自然语言提示词", List.of(cityArg));

        return new McpServerFeatures.SyncPromptSpecification(
                weatherPrompt,
                (McpSyncServerExchange exchange, McpSchema.GetPromptRequest req) -> {
                    Map<String, Object> args = req.arguments();
                    String city = (args != null && args.get("city") != null)
                            ? args.get("city").toString() : "深圳";
                    McpSchema.TextContent content = new McpSchema.TextContent(
                            "请帮我查询 " + city + " 当前的天气情况，包括温度、天气状况和湿度。");
                    McpSchema.PromptMessage message = new McpSchema.PromptMessage(
                            McpSchema.Role.USER, content);
                    return new McpSchema.GetPromptResult("天气查询提示词", List.of(message));
                });
    }
}
