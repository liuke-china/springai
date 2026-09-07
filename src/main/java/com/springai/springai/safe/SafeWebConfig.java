package com.springai.springai.safe;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 安全 Web 配置：把限流拦截器挂到 AI 接口上。
 *
 * 说明：Spring Boot 会自动注册 Filter 类型的 Bean（AuditLogFilter），
 * 所以审计过滤器无需在此手动注册；这里只注册限流拦截器（Interceptor 需显式 add）。
 */
@Configuration
public class SafeWebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    public SafeWebConfig(RateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/ai/**")   // 只限 AI 相关接口
                .order(1);
    }
}
