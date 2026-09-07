package com.springai.springai.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 轻量 RAG 业务指标埋点（可观测性学习目标）
 * =====================================================================
 * 只封装 Micrometer（指标埋点库）的 4 个业务指标，刻意不过度设计：
 *   - 不引 Prometheus 依赖（Actuator 原生 /actuator/metrics 已够前端读）
 *   - 不做 P95/P99 内存蓄水池（直接用 Micrometer Timer 的 publishPercentiles 算 p99）
 *   - 不算 token 成本（那是业务账单，不是监控指标）
 *
 * 指标一览（经 Actuator 自动暴露，前端 metrics.html 直读 /actuator/metrics/{name}）：
 *   - rag.ask.latency        （Timer，发布 p99）：RAG 端到端问答延迟 → 对应「QPS(=count) + P99 延迟」
 *   - rag.retrieval.latency  （Timer，发布 p99）：检索阶段耗时（向量召回+BM25+RRF+rerank）→ 对应「检索耗时」
 *   - rag.token.input        （Counter）：累计输入 token 数 → 对应「Token 用量」
 *   - rag.token.output       （Counter）：累计输出 token 数 → 对应「Token 用量」
 */
@Component
public class RagMetrics {

    private final Timer askTimer;
    private final Timer retrievalTimer;
    private final Counter tokenInput;
    private final Counter tokenOutput;

    public RagMetrics(MeterRegistry registry) {
        this.askTimer = Timer.builder("rag.ask.latency")
                .description("RAG 端到端问答延迟（发布 p99 分位）")
                .publishPercentiles(0.99)
                .register(registry);
        this.retrievalTimer = Timer.builder("rag.retrieval.latency")
                .description("RAG 检索阶段延迟（向量召回+BM25+RRF+rerank，发布 p99）")
                .publishPercentiles(0.99)
                .register(registry);
        this.tokenInput = Counter.builder("rag.token.input")
                .description("RAG 生成累计输入 token 数")
                .register(registry);
        this.tokenOutput = Counter.builder("rag.token.output")
                .description("RAG 生成累计输出 token 数")
                .register(registry);
    }

    /** 记录一次 RAG 问答的端到端耗时（纳秒） */
    public void recordAsk(long nanos) {
        askTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    /** 记录一次检索阶段的耗时（纳秒） */
    public void recordRetrieval(long nanos) {
        retrievalTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    /** 记录本次生成的 token 消耗（input/output 各自累加） */
    public void recordTokens(int inputTokens, int outputTokens) {
        if (inputTokens > 0) tokenInput.increment(inputTokens);
        if (outputTokens > 0) tokenOutput.increment(outputTokens);
    }
}
