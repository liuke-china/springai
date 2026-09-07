package com.springai.springai.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * P0-1 鉴权层（Spring Security 6.x）。
 *
 * 设计取舍（拿去面试能讲清楚）：
 *  1) 这是"无状态 API"：关掉 CSRF（浏览器表单才需要，API 用 Token 不需要）、关掉 Session（STATELESS），
 *     不做任何表单登录 / 跳转。Token 不对就直接 401 JSON，干净。
 *  2) 鉴权方式 = Bearer API Key（后端自己校验），适合"没有统一网关、后端自己守门"的部署。
 *     如果生产是"网关统一鉴权、后端只信内网"（更常见的大厂做法），把 app.auth.enabled 设 false 即可——
 *     后端全放行，由网关去挡，避免双重鉴权。
 *  3) 受保护路由：/ai/**（聊天 + Text-to-SQL）、/actuator/**（指标 / 信息）、/mcp（若开启）。
 *  4) 放开：/actuator/health（K8s 存活 / 就绪探针必须无鉴权）、/error、favicon、静态资源。
 *  5) fail-fast（防裸奔上线）：enabled=true 但没配置 API_SECURITY_TOKEN，启动直接抛异常，绝不静默放行。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.auth.enabled:true}")
    private boolean authEnabled;

    @Value("${app.auth.api-token:}")
    private String apiToken;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // fail-fast：开了鉴权却没给 token，不允许启动（杜绝"以为开了其实裸奔"）
        if (authEnabled && (apiToken == null || apiToken.isBlank())) {
            throw new IllegalStateException(
                    "鉴权已启用(app.auth.enabled=true)但未配置 API Key：请设置环境变量 APP_AUTH_API_TOKEN（对应配置项 app.auth.api-token）后再启动。");
        }

        // 开发 / 调试可临时关闭：app.auth.enabled=false → 全部放行（仍保留 CSRF 关闭 + 无状态，避免误改行为）
        if (!authEnabled) {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
            return http.build();
        }

        ApiKeyAuthFilter apiKeyFilter = new ApiKeyAuthFilter(apiToken);

        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                // 健康检查放开（K8s liveness / readiness 探针需要无鉴权访问）
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // 业务与运维核心路由：必须带有效 API Key
                .requestMatchers("/ai/**", "/actuator/**", "/mcp", "/mcp/**").authenticated()
                // 错误页、favicon、静态资源放开
                .requestMatchers("/error", "/favicon.ico", "/static/**", "/webjars/**").permitAll()
                .anyRequest().permitAll()
            )
            .exceptionHandling(e -> e.authenticationEntryPoint(new ApiKeyAuthenticationEntryPoint()))
            // 在"授权过滤器"之前插入我们的 Bearer 校验
            .addFilterBefore(apiKeyFilter, AuthorizationFilter.class);

        return http.build();
    }
}
