package com.springai.springai.mcp.controller;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Client 演示：本应用作为【客户端】，连上另一个 MCP Server（这里连自己 /mcp）。
 * =====================================================================
 * 小节③核心：之前你只是"被调用方"(Server)，现在演示"调用方"(Client)。
 *
 * 开启方式（application.yml）：
 *   把 app.mcp.client-demo.enabled 改成 true（默认 false）
 *
 * 【为什么手动创建 Client 而不是用 spring.ai.mcp.client auto-config？】
 * auto-config 会在 Spring 启动时就连接远程 Server。如果是"自连"模式（连自己 /mcp），
 * 此时 Server 端点还没就绪 → 整个应用启动崩溃（鸡生蛋问题）。
 * 所以本 Controller 用 MCP SDK 手动创建 Client，在首次 API 请求时才连接
 * （此时 Server 肯定已经就绪了），完全避开启动时序问题。
 *
 * 【两个实例模式（推荐生产用法）】
 * 实例A(8081)当 Server，实例B(改端口如8082)当 Client 连 A 的 /mcp。
 * 这种场景下可以用 auto-config（因为 A 先启动，B 连 A 时 A 已就绪）。
 * =====================================================================
 */
@RestController
@RequestMapping("/mcp-client")
@ConditionalOnProperty(name = "app.mcp.client-demo.enabled", havingValue = "true")
public class McpClientDemoController {

    private final ChatClient chatClient;

    /** 要连的 MCP Server 地址（默认连自己） */
    @Value("${spring.ai.mcp.client.streamable-http.connections.weather-server.url:http://localhost:8081/mcp}")
    private String serverUrl;

    /** 手动创建的 Client（懒加载，首次请求时初始化） */
    private volatile McpSyncClient lazyClient;
    private volatile boolean initAttempted = false;

    public McpClientDemoController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 懒加载：首次调用时才创建 McpSyncClient 并连接。
     * synchronized 保证线程安全（多线程同时访问时只创建一次）。
     */
    private McpSyncClient  getClient() {
        if (lazyClient != null) {
            return lazyClient;
        }
        synchronized (this) {
            if (lazyClient != null) {
                return lazyClient;
            }
            try {
                System.out.println("[McpClientDemo] ▶ 正在连接 MCP Server: " + serverUrl);
                // 用 Streamable HTTP transport 连接
                var transport = HttpClientStreamableHttpTransport.builder(serverUrl).build();
                lazyClient = McpClient.sync(transport)
                        .requestTimeout(java.time.Duration.ofSeconds(30))
                        .build();
                // 初始化连接（发送 initialize）
                lazyClient.initialize();
                System.out.println("[McpClientDemo] ✓ MCP Client 连接成功");
            } catch (Exception e) {
                System.err.println("[McpClientDemo] ✗ MCP Client 连接失败: " + e.getMessage());
                e.printStackTrace();
            } finally {
                initAttempted = true;
            }
        }
        return lazyClient;
    }

    /** ① 列出通过 MCP Client 连到的远程 Server 暴露了哪些工具（证明 Client 发现成功） */
    @GetMapping("/tools")
    public Map<String, Object> listRemoteTools() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, String>> tools = new ArrayList<>();
        McpSyncClient client = getClient();
        if (client == null) {
            result.put("error", "无法连接到 MCP Server: " + serverUrl);
            result.put("hint", "确认目标 Server 已启动且地址正确");
            return result;
        }
        try {
            McpSchema.ListToolsResult toolsResult = client.listTools();
            for (McpSchema.Tool t : toolsResult.tools()) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", t.name());
                m.put("description", t.description());
                tools.add(m);
            }
            result.put("serverUrl", serverUrl);
            result.put("remoteToolCount", tools.size());
            result.put("remoteTools", tools);
        } catch (Exception e) {
            result.put("error", "列出工具失败: " + e.getMessage());
        }
        return result;
    }

    /** ② 直接通过 MCP Client 调远程 Server 的 getWeather（绕过 LLM，纯协议调用） */
    @GetMapping("/call")
    public Object callRemoteTool(@RequestParam String city) {
        McpSyncClient client = getClient();
        if (client == null) {
            return Map.of("error", "MCP Client 未连接", "serverUrl", serverUrl);
        }
        try {
            McpSchema.CallToolResult res = client.callTool(
                    new McpSchema.CallToolRequest("getWeather", Map.of("city", city)));
            if (res.content() != null) {
                for (McpSchema.Content c : res.content()) {
                    if (c instanceof McpSchema.TextContent tc) {
                        return tc.text();
                    }
                }
            }
            return res.structuredContent();
        } catch (Exception e) {
            return Map.of("error", "调用工具失败", "detail", e.getMessage());
        }
    }

    /**
     * ③ 让本地 LLM 通过 MCP Client 拿到的【远程工具】来回答天气问题。
     * 注意：本端点演示的是"纯协议调用"能力（/call），LLM 集成需要把远程工具
     * 转成 ToolCallback 再给 ChatClient——这在两实例模式下更自然（auto-config 自动完成）。
     * 自连模式建议用 /call 端点验证协议连通性即可。
     */
    @GetMapping("/ask")
    public String askViaRemote(@RequestParam("q") String question) {
        McpSyncClient client = getClient();
        if (client == null) {
            return "错误：MCP Client 未连接到 " + serverUrl + "，请确认 Server 已启动。";
        }
        try {
            // 先用 Client 直接调 getWeather 工具获取数据
            McpSchema.CallToolResult res = client.callTool(
                    new McpSchema.CallToolRequest("getWeather", Map.of("city", extractCity(question))));
            String weatherData = "";
            if (res.content() != null) {
                for (McpSchema.Content c : res.content()) {
                    if (c instanceof McpSchema.TextContent tc) {
                        weatherData = tc.text();
                        break;
                    }
                }
            }
            // 再让 LLM 把原始数据组织成自然语言回答
            return chatClient.prompt()
                    .system("你是天气助手。以下是通过 MCP 协议从远程 Server 获取的天气数据，请用自然语言总结回答用户问题。\n天气数据：" + weatherData)
                    .user(question)
                    .call()
                    .content();
        } catch (Exception e) {
            return "查询失败: " + e.getMessage();
        }
    }

    /** 从问题中提取城市名（简单启发式） */
    private String extractCity(String question) {
        String[] cities = {"深圳", "北京", "上海", "广州", "哈尔滨", "成都", "杭州", "武汉", "西安", "南京"};
        for (String city : cities) {
            if (question.contains(city)) return city;
        }
        return "深圳"; // 默认
    }
}
