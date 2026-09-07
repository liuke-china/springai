package com.springai.springai.safe;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 审计日志过滤器（L6/LLM02 输出处理 + L8 监控告警）
 *
 * 作用：对所有 AI 接口（/ai/**）记录"谁、什么时间、调了什么端点"的元数据，
 * 用于事后溯源、异常行为分析（如某 IP 短时间高频注入试探）。
 *
 * 安全要点：只记元数据，**绝不记请求体/响应体**（可能含用户隐私或注入攻击原文），
 * 这是隐私合规（PIPL，个人信息保护法）的基本要求。
 *
 * 企业进阶：这类审计应接入统一的日志/追踪系统（如 OpenTelemetry + ELK / 阿里云 SLS），
 * 并对接告警（同一会话异常模式触发告警）。
 */
@Component
public class AuditLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuditLogFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/ai/")) {
            // 仅记元数据，脱敏、可审计
            log.info("[AUDIT] {} {} client={}", request.getMethod(), path, request.getRemoteAddr());
        }
        filterChain.doFilter(request, response);
    }
}
