package com.springai.springai.agent;

import com.springai.springai.memory.tool.ImsDeviceTool;
import com.springai.springai.demo.service.TableSchemaService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多工具自然语言查询 Agent（智能体）。
 *
 * 和之前版本最大的区别：
 *   之前是写死流程（永远 查表结构→生成SQL→执行），模型没有决策权，所以看起来像普通问答；
 *   现在是 ReAct（推理-行动）循环，模型每一步自己决定调哪个工具：
 *     FIND_SCHEMA / RUN_SQL / DEVICE_STATUS / ANSWER
 *   这才是"能看出是 Agent"的地方——模型在动态选择工具，trace（轨迹）里能看到它调了谁。
 *
 * 循环：思考 → 选工具 → 执行 → 结果喂回 → 再思考 ... 直到 ANSWER 或步数上限。
 *
 * 安全：RUN_SQL 仍走代码级校验（只允许 SELECT/WITH、必须 LIMIT<=1000、禁写操作/堆叠/注释），
 *       不能只靠 Prompt（提示词）约束；工具执行失败也作为观察结果喂回模型让它换思路重试。
 *
 * 记忆：Agent 自己管理 chat_memory（不走全局 Advisor），保证落库内容是人类可读的
 *       "Q: 原始问题 / A: 自然语言答案"，而不是模型内部的 JSON 动作。
 *       原因：全局 MessageChatMemoryAdvisor 会把模型返回的 JSON 动作原样存成 ASSISTANT，
 *       用户看到的是 thought|tool|answer 噪声而非真实答案。
 *       所以这里用 ChatClient.builder(chatModel) 建客户端（不带全局记忆 Advisor），
 *       在 run() 结束时手动写一对干净的 USER/ASSISTANT 到 SPRING_AI_CHAT_MEMORY。
 */
@Service
public class NaturalLanguageQueryAgent {

    private static final int DEFAULT_MAX_STEPS = 4;
    private static final int MAX_ALLOWED_STEPS = 6;

    private static final Pattern DANGEROUS_SQL = Pattern.compile(
            "\\b(DELETE|UPDATE|INSERT|DROP|TRUNCATE|ALTER|CREATE|GRANT|REVOKE|MERGE|CALL|DO)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT_PATTERN = Pattern.compile(
            "\\bLIMIT\\s+([1-9][0-9]{0,5})\\b", Pattern.CASE_INSENSITIVE);
    // 从模型最终答案抽取 SQL：代码块优先，否则取首个 SELECT/WITH 语句
    private static final Pattern CODE_BLOCK_SQL = Pattern.compile(
            "(?is)```(?:sql)?\\s*(.*?)```");
    private static final Pattern RAW_SQL = Pattern.compile(
            "(?is)((?:SELECT|WITH)\\b[\\s\\S]*?)(?:;|$)");

    private final ChatClient chatClient;
    private final JdbcTemplate jdbcTemplate;
    private final TableSchemaService tableSchemaService;
    private final ImsDeviceTool imsDeviceTool;
    private final ChatMemory chatMemory;

    // 决策器（DecisionMaker）：真实运行让模型决定；单测时注入 stub（桩）模拟模型。
    private DecisionMaker decisionMaker;

    public NaturalLanguageQueryAgent(ChatModel chatModel,
                                     JdbcTemplate jdbcTemplate,
                                     TableSchemaService tableSchemaService,
                                     ImsDeviceTool imsDeviceTool,
                                     ChatMemory chatMemory) {
        // 关键架构点：用 ChatClient.builder(chatModel) 自己 new 一个客户端——【刻意不带】全局记忆 Advisor。
        // 类比：不用 Spring 自动注入的、挂了全局拦截器的 Bean，而是自己 new 一个"干净实例"。
        // 原因：全局 Advisor 会把模型每步返回的 JSON 动作原样存进 chat_memory 的 ASSISTANT 列（一堆 thought|tool 噪声）。
        // 我们要的是"干净的人类可读 Q:A"，所以记忆自己管（见 run() 末尾 persistMemory()）。
        this.chatClient = ChatClient.builder(chatModel).build();
        this.jdbcTemplate = jdbcTemplate;
        this.tableSchemaService = tableSchemaService;
        this.imsDeviceTool = imsDeviceTool;
        this.chatMemory = chatMemory;
        this.decisionMaker = this::askModel; // 默认用真实模型决策
    }

    // 仅测试使用：用假决策器代替真实模型，避免依赖 LLM/API Key。
    void setDecisionMaker(DecisionMaker decisionMaker) {
        this.decisionMaker = decisionMaker;
    }

    /**
     * 决策器接口（Java 函数式接口）—— 决定"下一步动作"的策略。
     * 用你熟悉的设计模式说：这就是【策略模式】。同一个 decide() 方法有两种实现：
     *   真实运行：this::askModel —— 真去问 LLM（大语言模型）"下一步干啥"。
     *   单元测试：注入 stub（桩）—— 直接返回写死的假动作，不调 API 也能把循环逻辑测透。
     * 好处：业务循环(run)和"怎么决策"解耦，换模型/换测试数据都不用动循环代码。
     */
    @FunctionalInterface
    interface DecisionMaker {
        AgentAction decide(String question, String schema, List<ToolObservation> history,
                           List<String> conversation, String conversationId);
    }

    /**
     * 执行一次 Agent 任务 = 跑一遍 ReAct 循环（这是整个 Agent 的心脏）。
     *
     * 和普通"写死流程"代码的本质区别（重点，用你熟悉的比喻）：
     *   普通代码：if(条件A) 调工具1; else 调工具2;   —— 下一步是【程序员写死】的。
     *   Agent 代码：action = decisionMaker.decide(...); —— 下一步是【模型决定】的，程序员只写"循环框架"。
     * 一句话：Agent（智能体）= 让 LLM（大语言模型）在循环里动态选工具 + 看结果 + 再决定。
     */
    public NaturalLanguageQueryResult run(String question, String schema, int maxSteps) {
        String q = (question == null) ? "" : question;
        if (question == null || question.isBlank()) {
            return fail("问题不能为空", 0, List.of(), List.of(), List.of(), null, q);
        }

        String actualSchema = schema == null || schema.isBlank() ? "public" : schema.trim();
        int steps = Math.min(Math.max(maxSteps, 1), MAX_ALLOWED_STEPS);
        List<String> trace = new ArrayList<>();
        List<String> toolsUsed = new ArrayList<>();
        List<ToolObservation> observations = new ArrayList<>();
        // conversation（会话）：记录本次任务中所有与 LLM 的 Q/A 交互（内部推理轨迹，调试用）
        List<String> conversation = new ArrayList<>();
        // conversationId：本次任务在 chat_memory 表的会话ID，用于回查全部交互。
        // 注意：SPRING_AI_CHAT_MEMORY.conversation_id 是 VARCHAR(36)，
        // 纯 UUID 正好 36 字符会顶满列宽，加前缀必超长报 PSQLException。
        // 这里去掉连字符(UUID 32 字符) + "ag" 前缀(2) = 34 字符，
        // 既保留 agent 来源标识，又守住 36 上限。
        String conversationId = "ag" + UUID.randomUUID().toString().replace("-", "");

        for (int step = 1; step <= steps; step++) {
            // ① 问模型："基于已知信息，下一步该调哪个工具？" —— 决策权在模型，不在代码
            AgentAction action = decisionMaker.decide(
                    question, actualSchema, observations, conversation, conversationId);
            trace.add("第" + step + "步：模型选择工具=" + action.tool()
                    + "，思考=" + (action.thought() == null ? "" : action.thought()));

            // ② 模型说"信息够了，给最终答案"（ANSWER 就是循环的【终止信号】）→ 结束
            if ("ANSWER".equals(action.tool())) {
                trace.add("第" + step + "步：模型给出最终答案，结束循环");
                // 从最终答案里抽取 SQL 填到 result.sql（修复之前"回答是空的"）
                String sql = extractSql(action.finalAnswer());
                String finalText = action.finalAnswer() != null ? action.finalAnswer() : "（模型未返回具体答案）";
                // 落库干净 Q:A + 给前端 answer 字段
                persistMemory(conversationId, q, finalText);
                return NaturalLanguageQueryResult.success(
                        sql, finalText, List.of(), List.of(),
                        step, trace, toolsUsed, conversation, conversationId,
                        formatAnswer(q, finalText));
            }

            // ③ 否则：执行模型选中的工具 → 工具结果作为"观察(Observation)"喂回下一轮
            //    这就是 ReAct 的 Act→Observe：模型看到结果后，下一步决策会更聪明
            toolsUsed.add(action.tool());
            ToolObservation observation = dispatch(action, actualSchema);
            observations.add(observation);
            trace.add("第" + step + "步：工具[" + observation.tool() + "]返回="
                    + truncate(observation.result(), 200));
        }

        // 达到最大步数仍未收敛：给一个明确的失败回答，而不是空白
        String err = "达到最大执行步数仍未得出答案（可把问题拆细或补充关键信息后重试）";
        persistMemory(conversationId, q, err);
        return fail(err, steps, trace, toolsUsed, conversation, conversationId, q);
    }

    /** 把干净的 "Q: 原始问题 / A: 答案" 写入 SPRING_AI_CHAT_MEMORY（USER + ASSISTANT 各一条）。 */
    private void persistMemory(String conversationId, String question, String answerText) {
        if (chatMemory == null) return;
        try {
            chatMemory.add(conversationId, List.of(
                    new UserMessage(question),
                    new AssistantMessage(answerText)));
        } catch (Exception e) {
            // 记忆写入失败不能影响主流程（回答已经生成并返回）
        }
    }

    /** 给前端的干净回答：固定格式 "Q: 原始问题\nA: 自然语言答案"。 */
    private static String formatAnswer(String question, String answerText) {
        return "Q: " + (question == null ? "" : question) + "\nA: " + (answerText == null ? "" : answerText);
    }

    /** 构造失败结果并带上 answer 字段（保持与前端点一致的 Q:A 格式）。 */
    private static NaturalLanguageQueryResult fail(String errorMessage, int steps,
                                                  List<String> trace, List<String> toolsUsed,
                                                  List<String> conversation, String conversationId,
                                                  String question) {
        return NaturalLanguageQueryResult.failure(
                errorMessage, steps, trace, toolsUsed, conversation, conversationId,
                formatAnswer(question, errorMessage));
    }

    /**
     * 分发执行（Dispatch）：按模型选的 tool 字段去真正调对应工具。
     *
     * 关键设计（ReAct 的"反思-重试"就靠它）：工具【执行失败也不抛异常】，
     * 而是包装成一条"观察结果"喂回循环 —— 模型下一轮看到"上一步失败了"，
     * 就能换个工具/换个参数重试。这正是 Agent 比"一次性问答"鲁棒的地方。
     */
    private ToolObservation dispatch(AgentAction action, String schema) {
        try {
            String tool = action.tool();
            if ("FIND_SCHEMA".equals(tool)) {
                // 工具1：根据关键词找相关表结构
                String keyword = String.valueOf(
                        action.args() == null ? "" : action.args().getOrDefault("keyword", ""));
                List<String> tables = tableSchemaService.findRelevantTables(keyword);
                String ddl = tableSchemaService.readTableSchema(schema, tables);
                return new ToolObservation("FIND_SCHEMA",
                        "相关表：" + tables + "\n结构：\n" + ddl);

            } else if ("RUN_SQL".equals(tool)) {
                // 工具2：执行只读 SQL（先代码级安全校验，再查库）
                String sql = String.valueOf(
                        action.args() == null ? "" : action.args().getOrDefault("sql", ""));
                validateReadOnlySql(sql);
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
                return new ToolObservation("RUN_SQL",
                        "执行成功，返回 " + rows.size() + " 行：" + rows);

            } else if ("DEVICE_STATUS".equals(tool)) {
                // 工具3：查 IMS 设备状态统计（调外部 API，不是查本地库）
                if (imsDeviceTool == null) {
                    return new ToolObservation("DEVICE_STATUS", "设备工具未配置");
                }
                Map<String, Integer> counts = imsDeviceTool.countDevicesByState().getByStateCn();
                return new ToolObservation("DEVICE_STATUS",
                        "IMS 设备状态统计：" + counts);

            } else {
                return new ToolObservation(tool, "未知工具，无法执行");
            }
        } catch (Exception e) {
            // 工具失败也作为观察结果，体现 ReAct 的"反思-重试"
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new ToolObservation(action.tool(), "工具执行失败：" + msg);
        }
    }

    /**
     * 真实决策器：真正"问 LLM（大语言模型）下一步该怎么做"的地方（生产环境走这里）。
     *
     * 后端同学最该懂的 4 个点：
     *   1) system prompt = 给模型的"工具说明书 + 角色设定"，类比【接口文档】。
     *   2) user = 用户原始问题，类比【请求体】。
     *   3) .call().entity(AgentAction.class) = 把模型返回的 JSON 字符串直接【反序列化】成 Java 对象，
     *      和 SpringMVC 用 @RequestBody 把 JSON 绑成实体是同一回事（底层都是 Jackson）。
     *   4) 模型返回的【不是最终答案】，而是"下一步动作" AgentAction：
     *      thought(为啥这么想) / tool(调哪个工具) / args(工具入参) / finalAnswer(只有选 ANSWER 时才有)。
     */
    private AgentAction askModel(String question, String schema,
                                List<ToolObservation> history,
                                List<String> conversation,
                                String conversationId) {
        String historyText = history.isEmpty() ? "（还没有调用过工具）" : history.toString();
        String prompt = """
                你是只读数据查询 Agent（智能体）。你可以选择调用工具来完成用户的数据查询问题。

                可用工具：
                1. FIND_SCHEMA(keyword)：根据关键词找相关数据库表及其字段结构。
                2. RUN_SQL(sql)：执行只读 SQL（只允许 SELECT 或 WITH，必须带 LIMIT 且不超过 1000）。
                3. DEVICE_STATUS()：查询 IMS 设备状态统计（各状态设备数量）。
                4. ANSWER(finalAnswer)：当你已经掌握足够信息时，给出最终自然语言答案并结束。

                当前 schema（数据库命名空间）：%s
                你已经掌握的信息（前面工具返回）：%s

                请只返回 JSON，不要 markdown，不要多余文字：
                {"thought":"这一步我想做什么","tool":"FIND_SCHEMA|RUN_SQL|DEVICE_STATUS|ANSWER","args":{...},"finalAnswer":"..."}
                """.formatted(schema, historyText);

        // 长上下文（工具说明 + schema + 历史）放 system，
        // .user(question) 只放【原始问题】。
        AgentAction action = chatClient.prompt()
                .system("你是安全的只读查询 Agent，只返回合法 JSON。\n\n" + prompt)
                .user(question)
                .call()
                .entity(AgentAction.class);

        // 记录本次与 LLM 的交互：Q=用户原始问题，A=模型这一步的决策（思考+选的工具+最终答案）
        String answerText = "thought=" + action.thought()
                + " | tool=" + action.tool()
                + (action.finalAnswer() != null ? " | answer=" + action.finalAnswer() : "");
        conversation.add("Q: " + question + "\nA: " + answerText);
        return action;
    }

    /**
     * 从模型最终答案里抽取 SQL，填到返回结果的 sql 字段（修复"返回数据回答是空的"）。
     * 优先匹配 ```sql 代码块，否则取第一个 SELECT/WITH 语句（到分号或结尾）。
     */
    private static String extractSql(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher block = CODE_BLOCK_SQL.matcher(text);
        if (block.find()) {
            String sql = block.group(1).trim();
            if (!sql.isBlank()) return sql;
        }
        Matcher raw = RAW_SQL.matcher(text);
        if (raw.find()) {
            return raw.group(1).trim();
        }
        return null;
    }

    /**
     * 代码级安全边界：不能只相信 Prompt（提示词）。
     */
    static void validateReadOnlySql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("模型没有生成 SQL");
        }

        String normalized = sql.trim();
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.contains(";")) {
            throw new IllegalArgumentException("禁止执行多条 SQL");
        }
        if (normalized.contains("--") || normalized.contains("/*") || normalized.contains("*/")) {
            throw new IllegalArgumentException("禁止执行带注释的动态 SQL");
        }
        if (DANGEROUS_SQL.matcher(normalized).find()) {
            throw new IllegalArgumentException("检测到危险 SQL 操作");
        }
        if (!normalized.matches("(?is)^(SELECT|WITH)\\b.*")) {
            throw new IllegalArgumentException("只允许 SELECT 或 WITH 查询");
        }

        var limitMatcher = LIMIT_PATTERN.matcher(normalized);
        if (!limitMatcher.find()) {
            throw new IllegalArgumentException("查询必须带 LIMIT");
        }
        if (Integer.parseInt(limitMatcher.group(1)) > 1000) {
            throw new IllegalArgumentException("LIMIT 不能超过 1000");
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) return "null";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    public static int defaultMaxSteps() {
        return DEFAULT_MAX_STEPS;
    }
}
