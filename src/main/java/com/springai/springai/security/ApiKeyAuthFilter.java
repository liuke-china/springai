package com.springai.springai.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Bearer API Key 校验过滤器（P0-1 鉴权层的核心）。
 *
 * 干什么：从请求头 Authorization: Bearer <token> 取出令牌，和启动时配置好的 api-token 比对。
 *   - 一致 → 往 SecurityContext 写入一个"已认证"身份（角色 ROLE_API），请求继续往后走。
 *   - 不一致 / 没带 → 什么都不写，交给后面的 authorizeHttpRequests 判定为"未认证"→ 走 401。
 *
 * 为什么用 OncePerRequestFilter：保证一次请求只过一遍，且能插在 Spring Security 过滤器链里。
 * 注意：这里"校验失败"不主动抛异常，而是留白让授权环节去拦——这样能统一走 AuthenticationEntryPoint 返回 JSON。
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final String expectedToken;

    public ApiKeyAuthFilter(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.length() > 7 && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            // 配置为空时不认证（配合 SecurityConfig 的 fail-fast，正常不会走到这）
            if (!expectedToken.isEmpty() && expectedToken.equals(token)) {
                var auth = new UsernamePasswordAuthenticationToken(
                        "api-client", null, AuthorityUtils.createAuthorityList("ROLE_API"));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        filterChain.doFilter(request, response);
    }
}
