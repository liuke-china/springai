package com.springai.springai.mcp.config;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * IMS 的 Resources（资源）与 Prompts（提示模板）注册 —— Demo 版。
 * =====================================================================
 * 目的：用一个真实场景证明 Resource / Prompt 不是摆设。
 *
 *  • Resource（imsStatusManualResource）：
 *    完整"设备状态码手册"（code 0~13 的含义 + 处理建议，约 300 字）。
 *    这类大块只读资料若塞进 @Tool 描述，会跟着每次调用发送、浪费 token；
 *    作为 Resource 可"按需拉取"——客户端/LLM 只在需要时读它进上下文。
 *
 *  • Prompt（deviceWeeklyReportPrompt）：
 *    "生成设备健康周报"模板，带 dateRange 参数。
 *    这是【用户触发】的（客户端 UI 按钮），不是 LLM 触发——人填日期就展开成
 *    完整提示词，不用每次手敲一长段。
 *
 * 注册方式：和天气那个 WeatherMcpResourcesPromptsConfig 完全一样 ——
 * 暴露 @Bean（SyncResourceSpecification / SyncPromptSpecification），
 * Spring AI auto-config 自动收集注册到 /mcp。
 * =====================================================================
 */
@Configuration
public class ImsMcpResourcesPromptsConfig {

    /** 设备状态码手册的完整文本（Resource 与 Demo 控制器共用，避免重复） */
    public static String buildStatusManual() {
        return """
                【IMS 设备状态码手册】（只读参考资料 · v1.0）
                code 是 IMS 内部状态码，stateCn 是给人看的中文名。

                - code=0  停机        ：设备空闲未运行，正常非生产状态。
                - code=1  暂停        ：生产临时挂起（如等料），可快速恢复。
                - code=2  热机        ：设备预热/校准中，尚未开始加工。
                - code=3  加工中      ：正在执行生产任务，正常稼动状态。
                - code=4  连接不上    ：IMS 与设备通信中断，需排查网络/PLC/网关。
                - code=11 小停机      ：短暂停机（<5min），多为换刀/上下料。
                - code=12 计划外停机  ：故障导致的非预期停机，需立即介入、记入 MTTR。
                - code=13 计划内停机  ：保养/换模等排程停机，属正常维护。

                运维优先级：code=4（失联）> code=12（故障停机）> code=11（小停机）
                > code=1/2（可恢复）> code=0/13（正常）> code=3（健康）。
                """;
    }

    /** 周报提示词模板（Prompt 与 Demo 控制器共用） */
    public static String buildWeeklyReportPrompt(String dateRange) {
        String range = (dateRange == null || dateRange.isBlank()) ? "最近 7 天" : dateRange;
        return String.format(
                "你是一名 IMS 设备运维助手。请基于【%s】的设备运行数据，生成一份《设备健康周报》：\n"
                + "1. 调用 countDevicesByState 统计各状态设备数量；\n"
                + "2. 调用 listDevicesByState 列出『连接不上』和『计划外停机』的设备名；\n"
                + "3. 调用 readDeviceStateManual 解释关键状态含义；\n"
                + "4. 输出：整体健康度评分(0-100)、异常设备清单、下周维护建议。\n"
                + "统计周期已固定为：%s",
                range, range);
    }

    /**
     * Resource（只读背景资料）：设备状态码手册。
     * 用 @Bean 暴露成 SyncResourceSpecification，auto-config 自动收集注册。
     */
    @Bean
    public McpServerFeatures.SyncResourceSpecification imsStatusManualResource() {
        McpSchema.Resource manual = McpSchema.Resource.builder()
                .uri("ims://device/status-manual")
                .name("ims-device-status-manual")
                .description("IMS 设备状态码完整手册：code 0~13 的含义、运维优先级与处理建议（只读背景资料）")
                .mimeType("text/plain")
                .build();

        return new McpServerFeatures.SyncResourceSpecification(
                manual,
                (McpSyncServerExchange exchange, McpSchema.ReadResourceRequest request) -> {
                    String uri = request.uri();
                    McpSchema.TextResourceContents content =
                            new McpSchema.TextResourceContents(uri, "text/plain", buildStatusManual());
                    return new McpSchema.ReadResourceResult(List.of(content));
                });
    }

    /**
     * Prompt（预定义提示模板）：设备健康周报，带 dateRange 参数。
     * 用 @Bean 暴露成 SyncPromptSpecification，auto-config 自动收集注册。
     */
    @Bean
    public McpServerFeatures.SyncPromptSpecification deviceWeeklyReportPrompt() {
        McpSchema.PromptArgument rangeArg =
                new McpSchema.PromptArgument("dateRange", "统计周期，如 2026-08-01~2026-08-07", true);
        McpSchema.Prompt reportPrompt = new McpSchema.Prompt(
                "device-weekly-report", "生成一份 IMS 设备健康周报的提示词模板", List.of(rangeArg));

        return new McpServerFeatures.SyncPromptSpecification(
                reportPrompt,
                (McpSyncServerExchange exchange, McpSchema.GetPromptRequest req) -> {
                    Map<String, Object> args = req.arguments();
                    String dateRange = (args != null && args.get("dateRange") != null)
                            ? args.get("dateRange").toString() : "最近 7 天";
                    McpSchema.TextContent content =
                            new McpSchema.TextContent(buildWeeklyReportPrompt(dateRange));
                    McpSchema.PromptMessage message =
                            new McpSchema.PromptMessage(McpSchema.Role.USER, content);
                    return new McpSchema.GetPromptResult("设备健康周报提示词", List.of(message));
                });
    }
}
