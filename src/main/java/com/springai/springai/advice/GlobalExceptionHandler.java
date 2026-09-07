package com.springai.springai.advice;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.ai.retry.NonTransientAiException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理（P0-4）。
 *
 * 为什么要它：之前异常（比如 402 余额不足）会直接把原始堆栈/报错甩给前端，既不雅观也泄露内部实现。
 * 这里把所有异常归一成统一 JSON：服务端记完整堆栈排查，客户端只拿到"什么错 + 能不能重试"。
 *
 * 处理优先级（从上到下匹配）：
 *   1. AI/LLM 非瞬态错误（余额/鉴权）→ 503
 *   2. 上游 HTTP 错误（MiniMax/SiliconFlow）→ 4xx/5xx 映射
 *   3. 参数校验/解析失败 → 400
 *   4. ResponseStatusException（业务主动抛，如运维端点禁用）→ 用其状态码
 *   5. 数据库异常 → 500
 *   6. 兜底其他 → 500（不暴露原始 message）
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** LLM/AI 侧非瞬态错误（余额不足、鉴权失败等）：对客户端统一成 503，提示稍后重试 */
    @ExceptionHandler(NonTransientAiException.class)
    public ResponseEntity<Map<String, Object>> handleAi(NonTransientAiException ex, HttpServletRequest req) {
        serverLog(ex, req);
        return build(HttpStatus.SERVICE_UNAVAILABLE, "AI_SERVICE_UNAVAILABLE",
                "AI 服务暂时不可用，请稍后重试", req.getRequestURI());
    }

    /** 外部 HTTP 调用异常（MiniMax/SiliconFlow 等）：4xx→客户端错，5xx→上游/服务端错 */
    @ExceptionHandler(HttpStatusCodeException.class)
    public ResponseEntity<Map<String, Object>> handleHttp(HttpStatusCodeException ex, HttpServletRequest req) {
        serverLog(ex, req);
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String msg = status.is5xxServerError()
                ? "调用上游服务失败，请稍后重试"
                : "请求被上游拒绝（" + status.value() + "）";
        return build(status, "UPSTREAM_ERROR", msg, req.getRequestURI());
    }

    /** 参数校验失败（@Valid）或请求体解析失败（如 JSON 格式错） */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception ex, HttpServletRequest req) {
        serverLog(ex, req);
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "请求参数错误：" + ex.getMessage(), req.getRequestURI());
    }

    /** 业务主动抛出的受控错误（运维端点禁用用的就是它） */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex, HttpServletRequest req) {
        serverLog(ex, req);
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String reason = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        return build(status, "CLIENT_ERROR", reason, req.getRequestURI());
    }

    /**
     * 路由不存在（如被 @ConditionalOnProperty 关掉的运维端点）→ 404，而非 500。
     * Spring 对"无匹配 Handler"抛 NoResourceFoundException（注意：它在本版本不继承 ResponseStatusException，
     * 会被兜底 handleAll 误判成服务器错误），这里单独兜底成干净的 404。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoResourceFoundException ex, HttpServletRequest req) {
        serverLog(ex, req);
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "资源不存在", req.getRequestURI());
    }

    /** 数据库访问异常 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDb(DataAccessException ex, HttpServletRequest req) {
        serverLog(ex, req);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "DB_ERROR", "数据库访问异常，请稍后重试", req.getRequestURI());
    }

    /** 兜底：所有未归类异常 → 500，不暴露原始 message */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAll(Exception ex, HttpServletRequest req) {
        serverLog(ex, req);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误，请稍后重试", req.getRequestURI());
    }

    /** 服务端记完整堆栈（4th 参数 = throwable，slf4j 会打印堆栈）；客户端拿不到这些 */
    private void serverLog(Exception ex, HttpServletRequest req) {
        log.error("[GlobalException] {} {} -> {}", req.getMethod(), req.getRequestURI(), ex.toString(), ex);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String code, String message, String path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", true);
        body.put("errorType", code);
        body.put("message", message);
        body.put("path", path);
        body.put("timestamp", LocalDateTime.now().toString());
        return ResponseEntity.status(status).body(body);
    }
}
