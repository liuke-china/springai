package com.springai.springai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 未带 / 带错 API Key 时统一返回 401 JSON。
 *
 * 结构刻意和 GlobalExceptionHandler 对齐（{error, errorType, message, path, timestamp}），
 * 这样无论是"业务异常"还是"没带 token 被拦"，客户端拿到的错误体长得一样，前端好处理。
 *
 * 它会在"受保护路由被未认证请求命中"时被 Spring Security 的 ExceptionTranslationFilter 调起，
 * 直接往响应写 JSON（不走 DispatcherServlet，避免被全局异常处理器二次包装）。
 */
public class ApiKeyAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", true);
        body.put("errorType", "UNAUTHORIZED");
        body.put("message", "缺少或无效的 API Key（请在 Authorization 头携带 Bearer <token>）");
        body.put("path", request.getRequestURI());
        body.put("timestamp", LocalDateTime.now().toString());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
