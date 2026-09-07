package com.springai.springai.text2sql;

import com.alibaba.excel.EasyExcel;
import com.springai.springai.smalldemo.entity.ReActExecutionResult;
import com.springai.springai.smalldemo.entity.SqlResult;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * Text-to-SQL 对外服务端点（生产环境常开）。
 *
 * 【端点总览】
 *   生成/执行类（对外）
 *     /generate               只生成不执行
 *     /generate-detail        生成 + 召回明细 + 摘要
 *     /query                  生成 + 执行 + Excel 导出
 *     /query-react            生成 + 自纠错 + 执行
 *     /trace                  全链路 Trace
 *
 *   运维/管理类（/refresh-*、/audit、/schema-snapshots、/suggestions）
 *     → 已拆到 TextToSqlOpsController，由 app.ops-endpoints.enabled 开关控制，生产默认关闭。
 *
 * 注意：本控制器无鉴权（P0-1 待补 Spring Security）。当前靠网络层（nginx 内网/Token）
 * 兜底，对外暴露前必须先加认证层。
 */
@Slf4j
@RestController
@RequestMapping("/ai/sql")
@CrossOrigin(origins = "*")   // 生产应改为前端域名白名单（见生产清单 P1-9）
public class TextToSqlController {

    private final TextToSqlOrchestrator orchestrator;

    // ===== 限流 & 缓存（企业级生产加固）=====
    /** 并发信号量：最多同时处理 3 个请求，防把库查爆 */
    private final Semaphore concurrencyLimiter = new Semaphore(3);
    /** 简单 QPS 窗口：每秒最多 5 次，防 LLM/DB 成本失控 */
    private final Object qpsLock = new Object();
    private long qpsWindowStart = 0;
    private int qpsCount = 0;
    private static final int QPS_LIMIT = 5;
    /** 查询缓存：相同问题+相同开关组合直接返回，降本提速（TTL 5 分钟） */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;

    /** 缓存条目（带过期时间） */
    private static class CacheEntry {
        final Map<String, Object> value;
        final long expireAt;
        CacheEntry(Map<String, Object> value) {
            this.value = value;
            this.expireAt = System.currentTimeMillis() + CACHE_TTL_MS;
        }
        boolean alive() { return System.currentTimeMillis() < expireAt; }
    }

    public TextToSqlController(TextToSqlOrchestrator orchestrator, JdbcTemplate jdbcTemplate) {
        this.orchestrator = orchestrator;
    }

    // ==================== 生成/执行类 ====================

    @PostMapping("/generate")
    public SqlResult generateOnly(@RequestBody Map<String, String> request) {
        TextToSqlRequest req = buildRequest(request);
        req.setExecute(false);
        return orchestrator.run(req).getSqlResult();
    }

    @PostMapping("/generate-detail")
    public Map<String, Object> generateWithDetail(@RequestBody Map<String, String> request) {
        // 1) 查缓存：相同问题+相同开关组合，直接返回（fromCache=true）
        String cacheKey = buildCacheKey(request);
        CacheEntry hit = cache.get(cacheKey);
        if (hit != null && hit.alive()) {
            Map<String, Object> cached = new LinkedHashMap<>(hit.value);
            cached.put("fromCache", true);
            return cached;
        }
        // 2) 限流：QPS 窗口 + 并发信号量（防把库查爆 / 成本失控）
        acquireQps();
        try {
            concurrencyLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("并发请求被中断", e);
        }
        try {
            TextToSqlRequest req = buildRequest(request);
            req.setExecute(true);
            TextToSqlTrace trace = orchestrator.run(req);
            Map<String, Object> resp = buildDetailResponse(trace, req);
            resp.put("fromCache", false);
            // 3) 写缓存
            cache.put(cacheKey, new CacheEntry(resp));
            return resp;
        } finally {
            concurrencyLimiter.release();
        }
    }

    /** 组装 generate-detail 的响应（含执行数据，前端才能画表格） */
    private Map<String, Object> buildDetailResponse(TextToSqlTrace trace, TextToSqlRequest req) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sqlResult", trace.getSqlResult());
        resp.put("data", trace.getData());   // 修复：把执行结果数据也返回，前端才能画表格
        resp.put("summary", trace.getSummary());
        resp.put("matchedExemplars", trace.getFewShot() == null ? List.of() : trace.getFewShot().matchedExemplars());
        resp.put("matchedTerms", trace.getGlossary() == null ? List.of() : trace.getGlossary().matchedTerms());
        resp.put("matchedForeignKeys", trace.getKnowledge() == null ? List.of() : trace.getKnowledge().matchedForeignKeys());
        resp.put("matchedInterfaceMaps", trace.getKnowledge() == null ? List.of() : trace.getKnowledge().matchedInterfaceMaps());
        resp.put("matchedWhereHints", trace.getWhereHint() == null ? List.of() : trace.getWhereHint().matchedTables());
        resp.put("matchedMetrics", trace.getMatchedMetrics());
        resp.put("queryPlan", trace.getQueryPlan());
        resp.put("errorType", trace.getErrorType() == null ? null : trace.getErrorType().name());
        resp.put("safety", trace.getSafety());
        resp.put("useQueryPlan", req.isUseQueryPlan());
        resp.put("useBusinessMetric", req.isUseBusinessMetric());
        return resp;
    }

    /** 简单 QPS 限流：每秒最多 QPS_LIMIT 次，超出则等待到下一窗口 */
    private void acquireQps() {
        synchronized (qpsLock) {
            long now = System.currentTimeMillis();
            if (now - qpsWindowStart >= 1000) {
                qpsWindowStart = now;
                qpsCount = 0;
            }
            if (qpsCount >= QPS_LIMIT) {
                long wait = 1000 - (now - qpsWindowStart);
                if (wait > 0) {
                    try { qpsLock.wait(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                qpsWindowStart = System.currentTimeMillis();
                qpsCount = 0;
            }
            qpsCount++;
        }
    }

    /** 缓存 key：问题 + 影响结果的开关组合 */
    private String buildCacheKey(Map<String, String> request) {
        return String.join("|",
                "q=" + request.getOrDefault("question", ""),
                "bm=" + request.getOrDefault("useBusinessMetric", "true"),
                "qp=" + request.getOrDefault("useQueryPlan", "false"),
                "sm=" + request.getOrDefault("summarize", "false"),
                "sc=" + request.getOrDefault("useSelfCorrection", "false"),
                "wh=" + request.getOrDefault("useWhereHint", "true"));
    }


    @PostMapping("/query")
    public void queryAndExport(@RequestBody Map<String, String> request, HttpServletResponse response) throws IOException {
        TextToSqlRequest req = buildRequest(request);
        req.setExecute(true);
        // 导出场景也开自纠错：AI 偶尔写错类型（如 state=3 应为 state='3'）让 EXPLAIN 失败，重试一次能救回来
        req.setUseSelfCorrection(true);
        TextToSqlTrace trace = orchestrator.run(req);
        if (trace.getData() == null || trace.getData().isEmpty()) {
            response.setContentType("application/json;charset=UTF-8");
            String msg = trace.getExecuteError() != null
                    ? "{\"errorType\":\"" + trace.getErrorType() + "\",\"message\":\"执行异常：" + trace.getExecuteError() + "\"}"
                    : "{\"errorType\":\"EMPTY_RESULT\",\"message\":\"查询结果为空，没有数据可导出\"}";
            response.getWriter().write(msg);
            return;
        }
        try {
            exportExcel(response, trace.getData(), req.getQuestion());
        } catch (Exception e) {
            // Excel 导出失败。坑：response 可能已经被 Excel writer 部分写入（commit 了），
            // 此时再调 response.reset() 会抛 IllegalStateException（Cannot call reset() after response has been committed），
            // 而且客户端已经收到部分 xlsx 也无法撤回。所以根治策略=让 exportExcel 内部不抛（normalizeForExcel 转所有时间类型），
            // 万一还是抛了，我们只在还没 commit 时才回滚成 JSON。
            log.warn("[Text2Sql-Export] Excel 导出失败: {}", e.getMessage(), e);
            if (!response.isCommitted()) {
                response.reset();
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"errorType\":\"EXPORT_FAILED\",\"message\":\"Excel 导出失败：" + e.getMessage().replace("\"", "'") + "\"}");
            } else {
                log.error("[Text2Sql-Export] 响应已提交无法回滚，客户端会收到不完整 xlsx");
            }
        }
    }

    @PostMapping("/query-react")
    public ReActExecutionResult queryAndExportWithReact(@RequestBody Map<String, String> request) {
        TextToSqlRequest req = buildRequest(request);
        req.setExecute(true);
        req.setUseSelfCorrection(true);
        return orchestrator.run(req).getSelfCorrection();
    }

    @PostMapping("/trace")
    public TextToSqlTrace trace(@RequestBody TextToSqlRequest req) {
        return orchestrator.run(req);
    }

    // ==================== 解析 & 导出 ====================

    private TextToSqlRequest buildRequest(Map<String, String> request) {
        TextToSqlRequest req = new TextToSqlRequest();
        req.setQuestion(request.get("question"));
        req.setSchema(request.getOrDefault("schema", "public"));
        if (request.containsKey("useExemplar"))
            req.setUseExemplar(!("false".equalsIgnoreCase(request.get("useExemplar"))));
        if (request.containsKey("useCot"))
            req.setUseCot("true".equalsIgnoreCase(request.get("useCot")));
        if (request.containsKey("useGlossary"))
            req.setUseGlossary(!("false".equalsIgnoreCase(request.get("useGlossary"))));
        if (request.containsKey("useKnowledge"))
            req.setUseKnowledge(!("false".equalsIgnoreCase(request.get("useKnowledge"))));
        if (request.containsKey("useWhereHint"))
            req.setUseWhereHint(!("false".equalsIgnoreCase(request.get("useWhereHint"))));
        if (request.containsKey("useForeignKey"))
            req.setUseForeignKey(Boolean.parseBoolean(request.get("useForeignKey")));
        if (request.containsKey("useInterfaceMap"))
            req.setUseInterfaceMap(Boolean.parseBoolean(request.get("useInterfaceMap")));
        if (request.containsKey("useBusinessMetric"))
            req.setUseBusinessMetric(!("false".equalsIgnoreCase(request.get("useBusinessMetric"))));
        if (request.containsKey("useQueryPlan"))
            req.setUseQueryPlan("true".equalsIgnoreCase(request.get("useQueryPlan")));
        if (request.containsKey("summarize"))
            req.setSummarize("true".equalsIgnoreCase(request.get("summarize")));
        if (request.containsKey("maxRows"))
            req.setMaxRows(Integer.parseInt(request.get("maxRows")));
        if (request.containsKey("queryTimeoutSeconds"))
            req.setQueryTimeoutSeconds(Integer.parseInt(request.get("queryTimeoutSeconds")));
        if (request.containsKey("useSelfCorrection"))
            req.setUseSelfCorrection("true".equalsIgnoreCase(request.get("useSelfCorrection")));
        if (request.containsKey("maxRetries"))
            req.setMaxRetries(Integer.parseInt(request.get("maxRetries")));
        return req;
    }

    private void exportExcel(HttpServletResponse response,
                             List<Map<String, Object>> data,
                             String question) throws IOException {
        if (data.isEmpty()) {
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"查询结果为空\"}");
            return;
        }
        List<String> headers = new ArrayList<>(data.get(0).keySet());
        List<List<String>> headList = headers.stream().map(Collections::singletonList).map(ArrayList::new).collect(Collectors.toList());
        List<List<Object>> dataList = data.stream()
                .map(row -> headers.stream().map(row::get).collect(Collectors.toList()))
                .map(this::normalizeForExcel)  // 根治点：把 Timestamp/Date 等 EasyExcel 不认的类型转成字符串
                .collect(Collectors.toList());
        String fileName = "查询结果_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment;filename=" + encodedFileName + ".xlsx");
        EasyExcel.write(response.getOutputStream()).head(headList).sheet("查询结果").doWrite(dataList);
    }

    /**
     * 把一行数据里的非 Excel-friendly 类型（java.sql.Timestamp / LocalDateTime / Date 等）
     * 统一转成字符串，避免 EasyExcel 抛 "Can not find 'Converter' support class XXX"。
     */
    private List<Object> normalizeForExcel(List<Object> row) {
        return row.stream().map(v -> {
            if (v == null) return "";
            if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toString();
            if (v instanceof java.sql.Date sd) return sd.toLocalDate().toString();
            if (v instanceof java.sql.Time st) return st.toLocalTime().toString();
            if (v instanceof java.time.LocalDateTime ldt) return ldt.toString();
            if (v instanceof java.time.LocalDate ld) return ld.toString();
            if (v instanceof java.time.LocalTime lt) return lt.toString();
            if (v instanceof java.util.Date d) return d.toString();
            if (v instanceof byte[] bytes) return java.util.Base64.getEncoder().encodeToString(bytes);
            return v;
        }).collect(Collectors.toList());
    }
}
