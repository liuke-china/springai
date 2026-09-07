package com.springai.springai.safe;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限流拦截器（L8/LLM10 无限消耗防护）
 *
 * 用 HandlerInterceptor（Spring Web 原生，无需额外依赖）对 /ai/** 接口按客户端 IP 做令牌桶限流。
 * 超出阈值返回 HTTP 429。
 *
 * 企业常见写法对比：
 *   - 本类 = 单机 Spring 拦截器（适合单实例、快速落地）
 *   - AOP 切面（@Aspect + @Around + 自定义 @RateLimit）—— 写法更"业务感"，
 *     但需引入 aspectjweaver 依赖，本项目 pom 未含，故不强制用切面，避免编译失败。
 *   - 真正生产 = 在 API 网关（Nginx limit_req / Spring Cloud Gateway RequestRateLimiter / Redis 令牌桶）统一限流，
 *     因为网关层限流对所有服务、所有实例生效，且不怕水平扩容。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    /** 每 IP 桶容量与补充速率（可按业务调整：这里设为每秒 10、突发 30） */
    private final long capacity = 30;
    private final double refillPerSecond = 10;

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        // 生产环境若在 Nginx 后，应取 X-Forwarded-For 第一个 IP，而非 remoteAddr（可能是网关内网IP）
        String clientKey = request.getRemoteAddr();
        TokenBucket bucket = buckets.computeIfAbsent(clientKey,
                k -> new TokenBucket(capacity, refillPerSecond));
        if (!bucket.tryAcquire()) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"请求过于频繁，已被限流（RateLimit），请稍后再试\"}");
            return false;
        }
        return true;
    }
}
