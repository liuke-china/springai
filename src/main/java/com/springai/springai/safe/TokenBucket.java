package com.springai.springai.safe;

/**
 * 令牌桶限流器（L8/LLM10 无限消耗 / DoS 防护的算法核心）
 *
 * 原理：桶里最多 capacity 个令牌，按 refillTokensPerSecond 匀速补充。
 * 每个请求消耗 1 个令牌；桶空则拒绝（返回 429）。
 * 这是企业 API 网关（Nginx limit_req / Spring Cloud Gateway / Redis 令牌桶）的本地单实例实现。
 *
 * 生产说明：多实例部署时单机令牌桶不准，应改用 Redis + Lua 脚本做分布式限流，
 * 或直接在网关层（Nginx / APISIX / Spring Cloud Gateway）统一限流。
 */
public class TokenBucket {

    private final long capacity;
    private final double refillTokensPerSecond;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(long capacity, double refillTokensPerSecond) {
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    public synchronized boolean tryAcquire() {
        return tryAcquire(1);
    }

    public synchronized boolean tryAcquire(int n) {
        refill();
        if (tokens >= n) {
            tokens -= n;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSec > 0) {
            tokens = Math.min(capacity, tokens + elapsedSec * refillTokensPerSecond);
            lastRefillNanos = now;
        }
    }
}
