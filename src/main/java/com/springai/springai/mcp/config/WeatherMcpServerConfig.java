package com.springai.springai.mcp.config;

import com.springai.springai.mcp.server.WeatherMcpTools;
import com.springai.springai.memory.tool.ImsDeviceTool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Server 配置：把天气工具 + IMS 设备工具注册成标准 MCP 工具
 * =====================================================================
 * 【这一步是"魔法"发生的地方】
 * - WeatherMcpTools / ImsDeviceTool 里的 @Tool 方法，本身只是普通方法；
 * - 我们用 MethodToolCallbackProvider 把它们"打包"成一个
 *   ToolCallbackProvider（工具回调提供者）Bean；
 * - Spring AI 的 MCP Server 自动配置（ToolCallbackConverterAutoConfiguration）
 *   会扫描所有 ToolCallbackProvider Bean，并自动把它们转成 MCP 协议里的
 *   "tool" 定义，注册到 /mcp 端点上。
 *
 * 【结果】
 * - 启动后，任何 MCP 客户端访问 http://localhost:8081/mcp ，
 *   都能"列出工具"看到 getWeather / getTemperature / queryDevices /
 *   countDevicesByState 等 7 个工具，并"调用"它们。
 *
 * - IMS 5 个工具：queryDevices / countDevicesByState / listDevicesByState
 *   / readDeviceStateManual / buildDeviceSummaryPrompt
 * - Weather 2 个工具：getWeather / getTemperature
 * =====================================================================
 */
@Configuration
public class WeatherMcpServerConfig {

    /**
     * 把 WeatherMcpTools 暴露为 MCP 工具（2 个：getWeather / getTemperature）。
     */
    @Bean
    public MethodToolCallbackProvider weatherToolProvider(WeatherMcpTools weatherMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(weatherMcpTools)
                .build();
    }

    /**
     * 把 ImsDeviceTool 暴露为 MCP 工具（5 个：设备查询 + 状态统计 + 说明书 + 提示模板）。
     * 这样外部 MCP Client（如另一个 Spring 应用、Claude Desktop）就能
     * 通过标准 MCP 协议远程调用你公司的 IMS 设备查询能力。
     */
    @Bean
    public MethodToolCallbackProvider imsToolProvider(ImsDeviceTool imsDeviceTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(imsDeviceTool)
                .build();
    }
}
