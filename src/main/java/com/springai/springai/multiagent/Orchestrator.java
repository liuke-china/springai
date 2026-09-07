package com.springai.springai.multiagent;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Orchestrator（编排者）：把 Planner / Executor / Critic 三个角色串起来跑闭环。
 * 类比：项目经理——自己不写代码、不拆需求、不做 QA，只负责"按流程把三个人组织起来"。
 *
 * 闭环（多 Agent 的精髓 = 反思-重试）：
 *   Planner 出计划 → Executor 逐个子任务执行 → Critic 评审 →
 *   没过就把 Critic 的 feedback 回灌给 Planner 重新规划 → 再执行/再评审 ...
 *   直到 "通过" 或 "达到最大轮数"。
 *
 * ⚠️ 性能/健壮性关键（本类核心修复点，2026-08-05）：
 *   多 Agent 把"一次问题"拆成多个子任务，每个子任务又是一个完整 ReAct 循环，
 *   调用次数 = (1 Planner + N子任务×每子任务步数 + 1 Critic) × 轮数。
 *   在慢 LLM（如慢速大模型，单次 5~10s）下会被乘爆 → 请求挂好几分钟不出结果。
 *   所以这里加了三道保险：
 *     1) 子任务步数封顶 SUBTASK_MAX_STEPS=4（子任务都是单一聚焦问题，4 步足够）；
 *     2) 轮次级 deadline：每轮开始前检查墙钟，超时即停、返回当前最优；
 *     3) 整体 Future 超时兜底：任一单次 LLM 卡死也不会无限等待。
 *   超时配置可在 application.yml 用 app.multiagent.timeout-ms 调整（默认 90000ms）。
 */
@Service
public class Orchestrator {

    private static final int DEFAULT_MAX_ROUNDS = 2;
    private static final int MAX_ALLOWED_ROUNDS = 3;
    /**
     * 每个子任务内部的 ReAct 步数上限：子任务都是单一聚焦问题，4 步足够。
     * 防止用户把 maxSteps 设很大时，N 个子任务 × 大步数 把 LLM 调用次数乘爆。
     */
    private static final int SUBTASK_MAX_STEPS = 4;

    private final Planner planner;
    private final Executor executor;
    private final Critic critic;
    private final ChatMemory chatMemory;
    /** 整体墙钟超时（毫秒），可用 app.multiagent.timeout-ms 覆盖。 */
    private final long timeoutMs;

    public Orchestrator(Planner planner, Executor executor, Critic critic, ChatMemory chatMemory,
                        @Value("${app.multiagent.timeout-ms:90000}") long timeoutMs) {
        this.planner = planner;
        this.executor = executor;
        this.critic = critic;
        this.chatMemory = chatMemory;
        this.timeoutMs = timeoutMs;
    }

    public MultiAgentResult run(String question, String schema, int maxSteps, int maxRounds) {
        final String q = (question == null) ? "" : question;
        final String actualSchema = (schema == null || schema.isBlank()) ? "public" : schema.trim();

        // 整体超时兜底：用 Future 包一层，正常情况会被 doRun 内的轮次级 deadline 先触发；
        // 若某次 LLM 调用真卡死，这里保证请求不会无限等待。
        try {
            return CompletableFuture.supplyAsync(
                            () -> doRun(q, actualSchema, maxSteps, maxRounds))
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return new MultiAgentResult(
                    "请求已超过 " + (timeoutMs / 1000) + " 秒整体超时（疑似 LLM 调用卡死），已中断。"
                            + "建议：减小 maxRounds / maxSteps，或检查模型服务连通性与延迟。",
                    List.of(), List.of(),
                    List.of("整体超时中断"), null);
        } catch (Exception e) {
            String msg = (e.getCause() != null && e.getCause().getMessage() != null)
                    ? e.getCause().getMessage() : e.getMessage();
            return new MultiAgentResult(
                    "编排执行异常：" + msg,
                    List.of(), List.of(),
                    List.of("异常：" + e.getClass().getSimpleName()), null);
        }
    }

    private MultiAgentResult doRun(String q, String actualSchema, int maxSteps, int maxRounds) {
        if (q.isBlank()) {
            return new MultiAgentResult("问题不能为空", List.of(), List.of(),
                    List.of("第0轮：问题为空，直接失败"), null);
        }

        int rounds = Math.min(Math.max(maxRounds, 1), MAX_ALLOWED_ROUNDS);
        // 子任务步数封顶，避免调用爆炸
        int subSteps = Math.min(Math.max(maxSteps, 1), SUBTASK_MAX_STEPS);

        // 会话ID：SPRING_AI_CHAT_MEMORY.conversation_id 是 VARCHAR(36)，
        // 去连字符的 UUID(32) + "ma"前缀(2) = 34 字符，守 36 上限。
        String conversationId = "ma" + UUID.randomUUID().toString().replace("-", "");
        // 轮次级 deadline：慢 LLM 累积超时到这里就停，返回当前最优（不挂好几分钟）
        long deadline = System.currentTimeMillis() + timeoutMs;

        List<String> planSteps = new ArrayList<>();
        List<String> criticLog = new ArrayList<>();
        List<StepResult> lastResults = List.of();
        boolean approved = false;
        String feedback = null; // 首轮 Critic 之前没有上一轮反馈

        for (int round = 1; round <= rounds; round++) {
            if (System.currentTimeMillis() > deadline) {
                criticLog.add("第" + round + "轮前：已达 " + (timeoutMs / 1000)
                        + "s 超时上限，返回当前最优结果");
                break;
            }
            // ① Planner 出计划（首轮 feedback=null，后续带回 Critic 意见）
            Plan plan = planner.plan(q, feedback);
            planSteps.add("第" + round + "轮计划：" + String.join(" | ", plan.steps()));

            // ② Executor 逐个子任务执行（底层复用单 Agent 的工具链，子任务步数已封顶）
            lastResults = executor.execute(plan, actualSchema, subSteps);

            // ③ Critic 评审
            CriticResult review = critic.review(q, lastResults);
            criticLog.add("第" + round + "轮评审：approved=" + review.approved()
                    + "，feedback=" + (review.feedback() == null ? "" : review.feedback()));

            if (review.approved()) {
                approved = true;
                break;
            }
            // ④ 不通过 → feedback 回灌下一轮 Planner，触发重新规划（反思-重试闭环）
            feedback = review.feedback();
        }

        // 组装最终答案：把所有子任务的回答拼起来，形成一段完整回复
        StringBuilder answer = new StringBuilder();
        answer.append("原始问题：").append(q).append("\n");
        if (!approved) {
            answer.append("（注：经过编排后仍未完全通过评审，或为超时返回，以下为当前最优结果）\n");
        }
        for (int i = 0; i < lastResults.size(); i++) {
            StepResult r = lastResults.get(i);
            answer.append("\n- 子任务 ").append(i + 1).append("：").append(r.step())
                    .append("\n  回答：").append(r.answer());
        }

        // 落库干净 Q:A（和单 Agent 一致：只存人类可读的原始问题+最终答案）
        persistMemory(conversationId, q, answer.toString().trim());

        return new MultiAgentResult(
                answer.toString().trim(),
                planSteps,
                lastResults,
                criticLog,
                conversationId);
    }

    private void persistMemory(String conversationId, String question, String answerText) {
        if (chatMemory == null) return;
        try {
            chatMemory.add(conversationId, List.of(
                    new UserMessage(question),
                    new AssistantMessage(answerText)));
        } catch (Exception ignored) {
            // 记忆写入失败不影响主流程
        }
    }

    public static int defaultMaxRounds() {
        return DEFAULT_MAX_ROUNDS;
    }
}
