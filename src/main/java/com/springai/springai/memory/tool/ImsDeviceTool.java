package com.springai.springai.memory.tool;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * IMS 设备查询 Tool（让 AI 调用 IMS REST API）
 *
 * 【MCP 三大原语映射：Tool / Resource / Prompt】
 * -------------------------------------------------------------------------
 * 前面 3 个 @Tool = Tool（工具/螺丝刀）：会调 IMS REST，有"做事"语义。
 * 下面新增 2 个 @Tool，分别承载 MCP 的 Resource 与 Prompt 语义：
 *
 *  • Resource（资源/说明书）= 只读数据块，AI 拿来"查阅"，无副作用。
 *    协议层走 resources/list + resources/read（用 URI 寻址）。
 *  • Prompt（提示/标准作业卡）= 预设提示模板，带参数，AI 拿来"照着做"。
 *    协议层走 prompts/list + prompts/get。
 *
 *  ❓ 为什么这里用 @Tool 来实现 Resource/Prompt，而不是真注解？
 *  → 本项目 Spring AI 1.1.4 + MCP SDK 0.17.0：
 *      - Tool 有 @Tool 注解可直达；
 *      - Resource/Prompt 在 1.1.4 没有 @McpResource / @McpPrompt 注解
 *        （SDK 0.17.0 尚未提供注解式 API，原生只能走 McpServerFeatures
 *         编程式注册 —— 见文末"进阶·真·原生写法"）。
 *    所以用 @Tool 承载其"语义"是最稳、能立刻跑起来、AI 也能直接调用的写法。
 *
 * 【对外暴露 5 个 Tool】
 * - queryDevices(name)          按名查全量设备列表           （Tool 语义）
 * - countDevicesByState()       按 stateCn 统计设备数量       （Tool 语义）
 * - listDevicesByState(state)   按 stateCn 列出设备名清单     （Tool 语义）
 * - readDeviceStateManual()     返回状态字典"说明书"          （Resource 语义·只读）
 * - buildDeviceSummaryPrompt(s) 生成"状态总结"标准提示词模板  （Prompt 语义·模板）
 *
 * 【进阶·真·原生 Resource/Prompt（下一步升级，非本文件必须）】
 *   严格按 MCP 协议把 Resource/Prompt 暴露成"一等公民"（而非用 @Tool 承载语义），
 *   需在独立 @Configuration 类里实现 McpSyncServerCustomizer：
 *       public void customize(McpServer.SyncSpecification<?> spec) {
 *           spec.resources(McpServerFeatures.SyncResourceSpecification.builder()...);
 *           spec.prompts(McpServerFeatures.SyncPromptSpecification.builder()...);
 *       }
 *   本文件先用 @Tool 让你"立刻能跑、能理解"；原生写法作为进阶路线。
 */
@Slf4j
@Component
public class ImsDeviceTool {

    private static final int MAX_FETCH = 10000;
    private static final int PAGE_SIZE = 100;

    private static final Map<String, String> STATE_DICT = new LinkedHashMap<>();
    static {
        STATE_DICT.put("0", "停机");
        STATE_DICT.put("1", "暂停");
        STATE_DICT.put("2", "热机");
        STATE_DICT.put("3", "加工中");
        STATE_DICT.put("4", "连接不上");
        STATE_DICT.put("11", "小停机");
        STATE_DICT.put("12", "计划外停机");
        STATE_DICT.put("13", "计划内停机");
    }

    private static String translateState(String code) {
        if (code == null) return "未知";
        String key = code.trim();
        return STATE_DICT.getOrDefault(key, code + "（未知）");
    }

    /**
     * 通用：打印调用入口 + 测量耗时 + 打印返回大小 + 异常也打印。
     * 只用 System.out，避免日志级别被刷掉。
     */
    private static <T> T measure(String methodName, Supplier<T> action) {
        long t0 = System.currentTimeMillis();
        System.out.println("[ImsDeviceTool] ▶ 调用 Tool: " + methodName);
        try {
            T result = action.get();
            long ms = System.currentTimeMillis() - t0;
            int size = -1;
            try {
                if (result instanceof DeviceQueryResult d) {
                    size = d.getDevices() == null ? 0 : d.getDevices().size();
                } else if (result instanceof StateCountResult s) {
                    size = s.getByStateCn() == null ? 0 : s.getByStateCn().size();
                } else if (result instanceof DeviceListByStateResult l) {
                    size = l.getDeviceNames() == null ? 0 : l.getDeviceNames().size();
                }
            } catch (Exception ignore) {}
            System.out.println("[ImsDeviceTool] ✓ 退出 Tool: " + methodName + "  耗时=" + ms + "ms" + (size >= 0 ? "  返回项=" + size : ""));
            return result;
        } catch (Throwable e) {
            long ms = System.currentTimeMillis() - t0;
            System.out.println("[ImsDeviceTool] ✗ 异常 Tool: " + methodName + "  耗时=" + ms + "ms  err=" + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    private final WebClient imsWebClient;

    public ImsDeviceTool(@Qualifier("imsWebClient") WebClient imsWebClient) {
        this.imsWebClient = imsWebClient;
    }

    @Tool(description = "查询 IMS 设备列表。"
            + "会自动翻页拉全量，最多 2000 条。"
            + "返回每台设备包含 stateCn（中文状态）和 deviceName（设备名）和create_time（创建时间）。"
            + "用户问'设备列表/所有设备/查设备清单/最新添加的x台设备'时使用。"
            + "注意：如果用户问'数量统计'，应该改用 countDevicesByState 而不是这个工具，效率高得多。")
    public DeviceQueryResult queryDevices(
            @ToolParam(description = "设备名称（可选模糊匹配，不传查全部）") String name) {
        return measure("queryDevices", () -> queryDevicesInternal(name));
    }

    private DeviceQueryResult queryDevicesInternal(String name) {
        if (name != null && name.isBlank()) {
            name = null;
        }
        try {
            List<Map<String, Object>> all = fetchAllPages(name);
            return new DeviceQueryResult(all, all.size(), "ims");
        } catch (Throwable e) {
            log.warn("[ImsDeviceTool] 调用 IMS 失败: {}，返回 mock 数据", e.getMessage());
            return getMockDevices(name);
        }
    }

    @Tool(description = "按设备状态聚合统计数量。"
            + "返回各 stateCn（中文状态）对应的设备数量，例：{\"加工中\": 1234, \"停机\": 200, ...}。"
            + "用于回答：'有几台运行中'、'停机和失联各几台'、'各状态分布' 等统计类问题。"
            + "比 queryDevices 快得多。")
    public StateCountResult countDevicesByState() {
        return measure("countDevicesByState", () -> countDevicesByStateInternal());
    }

    private StateCountResult countDevicesByStateInternal() {
        try {
            List<Map<String, Object>> all = fetchAllPages(null);
            Map<String, Integer> byStateCn = new LinkedHashMap<>();
            int total = 0;
            for (Map<String, Object> row : all) {
                Object stateCn = row.get("stateCn");
                String key = stateCn == null ? "未知" : stateCn.toString();
                byStateCn.merge(key, 1, Integer::sum);
                total++;
            }
            return new StateCountResult(byStateCn, total, "ims");
        } catch (Throwable e) {
            log.warn("[ImsDeviceTool] 调用 IMS 失败: {}，返回 mock 统计", e.getMessage());
            return getMockStateCounts();
        }
    }

    @Tool(description = "按设备状态筛选设备，返回该状态下的设备名（deviceName）清单。"
            + "用于回答：'运行中的设备分别叫什么'、'停机的有哪些'。"
            + "stateCn 传中文：'加工中'/'停机'/'暂停'/'热机'/'连接不上'/'小停机'/'计划外停机'/'计划内停机'。")
    public DeviceListByStateResult listDevicesByState(
            @ToolParam(description = "设备状态中文，如 '加工中'、'停机'、'连接不上'") String stateCn) {
        return measure("listDevicesByState(" + stateCn + ")", () -> listDevicesByStateInternal(stateCn));
    }

    private DeviceListByStateResult listDevicesByStateInternal(String stateCn) {
        if (stateCn == null || stateCn.isBlank()) {
            return new DeviceListByStateResult(List.of(), 0, "ims", "stateCn 不能为空");
        }
        try {
            List<Map<String, Object>> all = fetchAllPages(null);
            Map<String, List<String>> group = new LinkedHashMap<>();
            for (Map<String, Object> row : all) {
                Object sc = row.get("stateCn");
                String key = sc == null ? "未知" : sc.toString();
                group.computeIfAbsent(key, k -> new ArrayList<>()).add(
                        String.valueOf(row.getOrDefault("deviceName", row.getOrDefault("name", "?")))
                );
            }
            List<String> names = group.getOrDefault(stateCn.trim(), List.of());
            return new DeviceListByStateResult(names, names.size(), "ims", null);
        } catch (Throwable e) {
            log.warn("[ImsDeviceTool] listDevicesByState 调用 IMS 失败: {}", e.getMessage());
            return new DeviceListByStateResult(List.of(), 0, "mock", "IMS 调用失败: " + e.getMessage());
        }
    }

    // =========================================================================
    //  MCP Resource（资源）& Prompt（提示）语义实现
    //  （用 @Tool 承载其语义；原生 McpServerFeatures 注册见类注释"进阶"段）
    // =========================================================================

    /**
     * 【MCP Resource（资源）语义】= "说明书"（只读数据块）
     * ---------------------------------------------------------------------
     * 返回设备状态字典的"可读说明书"：把内部 code（0/1/2...）翻译成人能懂的中文状态，
     * 并解释每个状态的含义。纯读取 —— 不调 IMS、无副作用，这正是 Resource 的典型特征。
     * 类比：工作台上那本《设备状态说明书》，AI 遇到"这状态啥意思"随时翻。
     * 协议对照：原生 Resource 走 resources/list + resources/read（URI 寻址返回内容）。
     */
    @Tool(description = "返回 IMS 设备状态字典说明书（只读参考资料）。"
            + "列出全部状态 code 与对应中文（停机/暂停/热机/加工中/连接不上/小停机/计划外停机/计划内停机），"
            + "供 AI 自行核对'用户说的状态名对不对'、'某 code 是什么意思'。"
            + "这是只读资料，不产生任何副作用。")
    public String readDeviceStateManual() {
        return measure("readDeviceStateManual", () -> {
            StringBuilder sb = new StringBuilder();
            sb.append("【IMS 设备状态说明书】（只读参考资料）\n");
            sb.append("code 是 IMS 内部状态码，stateCn 是给人看的中文名：\n");
            for (Map.Entry<String, String> e : STATE_DICT.entrySet()) {
                sb.append("- code=").append(e.getKey()).append(" → ").append(e.getValue()).append("\n");
            }
            sb.append("含义速记：'加工中'=正在跑活；'连接不上'=IMS 失联需排查网络；'计划内停机'=正常保养。\n");
            return sb.toString();
        });
    }

    /**
     * 【MCP Prompt（提示）语义】= "标准作业卡"（预设模板 + 参数）
     * ---------------------------------------------------------------------
     * 给定一种设备状态，返回一段"可直接交给 LLM 的提示词模板"（Prompt）。
     * AI/用户拿到这段文本，直接喂给模型，就能稳定产出"该状态设备运行情况总结"，
     * 而不是每次都靠模型临场发挥。类比：工作台上那张《设备运行日报·标准作业卡》，
     * 填好状态栏就能照着出报告。
     * 协议对照：原生 Prompt 走 prompts/list + prompts/get（带参数返回模板）。
     */
    @Tool(description = "生成一份'设备状态运行总结'的标准提示词模板（Prompt）。"
            + "传入状态中文（如'加工中'），返回一段可直接交给 LLM 的提示词，"
            + "让它去调用 countDevicesByState / listDevicesByState / readDeviceStateManual 并总结。"
            + "用于：'帮我写个提示词，总结下停机设备'。")
    public String buildDeviceSummaryPrompt(
            @ToolParam(description = "设备状态中文，如 '加工中'、'停机'、'连接不上'") String stateCn) {
        return measure("buildDeviceSummaryPrompt(" + stateCn + ")", () -> {
            if (stateCn == null || stateCn.isBlank()) {
                return "错误：stateCn 不能为空。请传入状态中文，如 '加工中' / '停机'。";
            }
            String s = stateCn.trim();
            return String.format(
                    "你是一名 IMS 设备运维助手。请严格按步骤完成任务：\n"
                    + "1. 调用 countDevicesByState 工具，统计当前处于『%s』状态的设备数量；\n"
                    + "2. 调用 listDevicesByState 工具，列出这些设备的名称；\n"
                    + "3. 调用 readDeviceStateManual 工具，说明『%s』状态的含义；\n"
                    + "4. 用一句话总结：是否需要人工介入、潜在风险是什么。\n"
                    + "（状态参数已固定为：%s）",
                    s, s, s);
        });
    }

    private List<Map<String, Object>> fetchAllPages(String name) {
        List<Map<String, Object>> all = new ArrayList<>();
        int pageNum = 1;
        int totalFromIms = Integer.MAX_VALUE;

        while (all.size() < totalFromIms && all.size() < MAX_FETCH) {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("name", name);
            requestBody.put("pageNum", pageNum);
            requestBody.put("pageSize", PAGE_SIZE);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = imsWebClient.post()
                    .uri("/deviceManage/device/page")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();

            if (response == null) break;

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            if (data == null) break;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pageList = (List<Map<String, Object>>) data.get("list");
            if (pageList == null || pageList.isEmpty()) break;

            all.addAll(pageList);
            for (Map<String, Object> row : pageList) {
                Object stateCode = row.get("state");
                row.put("stateCn", translateState(stateCode == null ? null : stateCode.toString()));
                row.put("stateOriginal", stateCode);
            }

            if (pageNum == 1) {
                Object totalObj = data.get("total");
                if (totalObj instanceof Number n) {
                    totalFromIms = n.intValue();
                }
            }
            pageNum++;
        }
        return all;
    }

    private DeviceQueryResult getMockDevices(String name) {
        List<Map<String, Object>> mockList = new ArrayList<>();
        String[][] devices = {
            {"1", "CNC-001", "3", "A栋-1F"},
            {"2", "CNC-002", "3", "A栋-1F"},
            {"3", "CNC-003", "1", "A栋-2F"},
            {"4", "CNC-004", "3", "B栋-1F"},
            {"5", "CNC-005", "0", "B栋-2F"}
        };
        for (String[] d : devices) {
            if (name == null || d[1].contains(name)) {
                Map<String, Object> device = new HashMap<>();
                device.put("id", d[0]);
                device.put("deviceName", d[1]);
                device.put("name", d[1]);
                device.put("state", d[2]);
                device.put("stateCn", translateState(d[2]));
                device.put("stateOriginal", d[2]);
                device.put("location", d[3]);
                mockList.add(device);
            }
        }
        return new DeviceQueryResult(mockList, mockList.size(), "mock（IMS 调用失败）");
    }

    private StateCountResult getMockStateCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("加工中", 3);
        counts.put("暂停", 1);
        counts.put("停机", 1);
        return new StateCountResult(counts, 5, "mock");
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class DeviceQueryResult {
        private List<Map<String, Object>> devices;
        private Integer total;
        private String source;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class StateCountResult {
        private Map<String, Integer> byStateCn;
        private Integer total;
        private String source;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class DeviceListByStateResult {
        private List<String> deviceNames;
        private Integer total;
        private String source;
        private String error;
    }
}
