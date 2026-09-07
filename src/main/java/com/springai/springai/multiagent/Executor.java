package com.springai.springai.multiagent;

import com.springai.springai.agent.NaturalLanguageQueryAgent;
import com.springai.springai.agent.NaturalLanguageQueryResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Executor（执行者）：只负责"干"——按 Planner（规划者）给的子任务逐个执行。
 *
 * 关键复用点：每个子任务直接委托给你已经写好的单 Agent（NaturalLanguageQueryAgent.run），
 * 它的 ReAct 循环 + FIND_SCHEMA/RUN_SQL/DEVICE_STATUS 工具链全都复用，
 * 不用重复造轮子。也就是说——Executor 本身也是一个"子 Agent（智能体）"。
 *
 * 性能关键（2026-08-05 修复）：子任务【并发】执行，而非串行。
 *   之前是 for 循环一个个跑，墙钟 ≈ 所有子任务耗时之和；在慢 LLM（单次 5~10s）下，
 *   4 个子任务就 40s+，复杂问题直接突破超时。
 *   改用 Java 21 虚拟线程（virtual thread）执行器后，所有子任务同时跑，
 *   墙钟 ≈ 最慢那一个子任务，延迟（latency）降数倍。
 *   并发安全：每个子任务 agent.run() 内部自生成独立 conversationId（"ag"+UUID），
 *   ChatMemory 写互不冲突；ChatModel / JdbcTemplate 均为无状态或线程安全，可并发调用。
 */
@Service
public class Executor {

    private final NaturalLanguageQueryAgent agent;
    // Java 21 虚拟线程执行器：每个子任务一条轻量虚拟线程，不占 OS 线程、并发开销极低。
    private final ExecutorService vpool = Executors.newVirtualThreadPerTaskExecutor();

    public Executor(NaturalLanguageQueryAgent agent) {
        this.agent = agent;
    }

    /**
     * 并发执行整份计划：所有子任务同时跑，墙钟时间≈最慢那一个（而非全部相加）。
     */
    public List<StepResult> execute(Plan plan, String schema, int maxSteps) {
        List<CompletableFuture<StepResult>> futures = plan.steps().stream()
                .map(step -> CompletableFuture.supplyAsync(
                        () -> runOne(step, schema, maxSteps), vpool))
                .toList();

        // 按提交顺序收集结果（join 会等待各自完成）；单个子任务失败不影响其他
        List<StepResult> results = new ArrayList<>();
        for (CompletableFuture<StepResult> f : futures) {
            try {
                results.add(f.join());
            } catch (Exception e) {
                results.add(new StepResult("(并发子任务)", "执行失败：" + e.getMessage()));
            }
        }
        return results;
    }

    private StepResult runOne(String step, String schema, int maxSteps) {
        NaturalLanguageQueryResult r = agent.run(step, schema, maxSteps);
        String ans = (r.answer() != null && !r.answer().isBlank())
                ? r.answer() : r.errorMessage();
        return new StepResult(step, ans == null ? "" : ans);
    }
}
