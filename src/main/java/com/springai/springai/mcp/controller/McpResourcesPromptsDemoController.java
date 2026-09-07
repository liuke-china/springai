package com.springai.springai.mcp.controller;

import com.springai.springai.mcp.config.ImsMcpResourcesPromptsConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP Resources / Prompts 作用 Demo 控制器。
 * =====================================================================
 * 这些端点只是为了让你【肉眼 curl 看到】Resource / Prompt 里到底有什么、
 * 以及"没有 Resource 时 LLM 答不准、有 Resource 时答得准"的对比。
 *
 * MCP 协议本身的 resources/read、prompts/get 由 Spring AI auto-config 自动提供，
 * 这里用 REST 包一层只是方便验证，不是 MCP 协议调用。
 * =====================================================================
 */
@RestController
@RequestMapping("/mcp-demo")
public class McpResourcesPromptsDemoController {

    /** ① 看 Resource 里有什么：完整设备状态码手册 */
    @GetMapping("/resource")
    public Map<String, Object> showResource() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("uri", "ims://device/status-manual");
        r.put("type", "Resource（只读背景资料）");
        r.put("content", ImsMcpResourcesPromptsConfig.buildStatusManual());
        return r;
    }

    /** ② 看 Prompt 模板展开成什么：填日期 → 完整周报提示词 */
    @GetMapping("/prompt")
    public Map<String, Object> showPrompt(@RequestParam(defaultValue = "2026-08-01~2026-08-07") String dateRange) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", "device-weekly-report");
        r.put("type", "Prompt（用户触发的模板）");
        r.put("dateRange", dateRange);
        r.put("expandedPrompt", ImsMcpResourcesPromptsConfig.buildWeeklyReportPrompt(dateRange));
        return r;
    }

    /**
     * ③ 三个"需要 Resource 才能答好"的测试问题。
     * 每个返回：问题 + 仅看 @Tool 描述会怎样（答不准）+ 有 Resource 能提供什么（答得准）。
     */
    @GetMapping("/q1")
    public Map<String, Object> question1() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("question", "状态码 12 是什么意思？设备出现 12 我该怎么处理？");
        r.put("toolOnly", "只看 @Tool 的短描述，LLM 只知道'有一个状态说明书工具'，但不知道 12 具体含义，可能含糊回答或瞎编。");
        r.put("withResource", "读 Resource 手册 → 准确答出：code=12 是『计划外停机』，属故障导致的非预期停机，需立即介入、记入 MTTR（平均修复时间），并列为最高运维优先级之一。");
        return r;
    }

    @GetMapping("/q2")
    public Map<String, Object> question2() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("question", "加工中（3）和热机（2）有什么区别？哪个更该关注？");
        r.put("toolOnly", "Tool 描述里没有状态对比信息，LLM 只能凭常识猜，可能说反或漏掉运维优先级。");
        r.put("withResource", "读 Resource 手册 → 准确答出：加工中=正在执行生产任务（健康稼动）；热机=预热/校准中尚未加工。按运维优先级，二者都属可恢复/健康段，都不如 code=4/12 紧急。");
        return r;
    }

    @GetMapping("/q3")
    public Map<String, Object> question3() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("question", "车间有设备显示连接不上（4），我要怎么排查？");
        r.put("toolOnly", "Tool 短描述不解释故障处理，LLM 只能泛泛而谈'检查网络'，不具体。");
        r.put("withResource", "读 Resource 手册 → 准确答出：code=4 是 IMS 与设备通信中断，排查顺序应为 网络→PLC→网关；且其运维优先级最高（>计划外停机），应第一时间处理。");
        return r;
    }
}
