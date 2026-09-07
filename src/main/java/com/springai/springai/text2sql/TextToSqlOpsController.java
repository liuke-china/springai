package com.springai.springai.text2sql;

import com.springai.springai.demo.service.TableSchemaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 运维 / 知识库管理端点（刷新表结构、刷新范例、审计日志、Schema 快照、查询建议等）。
 *
 * ⚠️ 生产默认关闭：这些端点是内部运维能力，无鉴权时能被用来"投毒知识库"（上传恶意范例/术语）
 * 或泄露审计日志，故生产环境不暴露。
 * 开关：app.ops-endpoints.enabled（默认 false）。需要运维时设为 true，
 * 但务必只在【内网 + 已加鉴权】的环境下开启。
 *
 * 实现方式：@ConditionalOnProperty(matchIfMissing=false) —— 开关为 false 时整个 Bean 不创建，
 * 这些 @RequestMapping 也就不会注册，外部访问直接 404，而不是靠方法里逐个判空。
 */
@Slf4j
@RestController
@RequestMapping("/ai/sql")
@ConditionalOnProperty(name = "app.ops-endpoints.enabled", havingValue = "true", matchIfMissing = false)
public class TextToSqlOpsController {

    private final TableSchemaService tableSchemaService;
    private final QuerySuggestionService suggestionService;
    private final TextToSqlAuditService auditService;
    private final SchemaRegistryService schemaRegistry;

    public TextToSqlOpsController(TableSchemaService tableSchemaService,
                                  QuerySuggestionService suggestionService,
                                  TextToSqlAuditService auditService,
                                  SchemaRegistryService schemaRegistry) {
        this.tableSchemaService = tableSchemaService;
        this.suggestionService = suggestionService;
        this.auditService = auditService;
        this.schemaRegistry = schemaRegistry;
    }

    // ==================== 知识刷新类 ====================

    @PostMapping("/refresh-schema")
    public Map<String, Object> refreshSchema(
            @RequestParam(defaultValue = "public") String schema) {
        tableSchemaService.indexSchema(schema);
        String version = schemaRegistry.snapshot(schema);
        return Map.of("message", "表结构索引 + Schema 快照已刷新", "schema", schema, "snapshotVersion", version);
    }

    @PostMapping("/refresh-exemplars")
    public Map<String, Object> refreshExemplars(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "path", required = false) String path) {
        byte[] bytes = readFileBytes(file, path);
        int count = tableSchemaService.indexExemplars(bytes);
        return Map.of("message", "范例索引刷新完成", "count", count);
    }

    @PostMapping("/refresh-glossary")
    public Map<String, Object> refreshGlossary(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "path", required = false) String path) {
        byte[] bytes = readFileBytes(file, path);
        int count = tableSchemaService.indexGlossary(bytes);
        return Map.of("message", "业务术语字典索引刷新完成", "count", count);
    }

    @PostMapping("/refresh-foreign-keys")
    public Map<String, Object> refreshForeignKeys(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "path", required = false) String path) {
        byte[] bytes = readFileBytes(file, path);
        int count = tableSchemaService.indexForeignKeys(bytes);
        return Map.of("message", "表关联关系索引刷新完成", "count", count);
    }

    @PostMapping("/refresh-interface-map")
    public Map<String, Object> refreshInterfaceMap(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "path", required = false) String path) {
        byte[] bytes = readFileBytes(file, path);
        int count = tableSchemaService.indexInterfaceMap(bytes);
        return Map.of("message", "接口映射索引刷新完成", "count", count);
    }

    @PostMapping("/refresh-where-hints")
    public Map<String, Object> refreshWhereHints(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "path", required = false) String path) {
        byte[] bytes = readFileBytes(file, path);
        int count = tableSchemaService.indexWhereHints(bytes);
        return Map.of("message", "源码 WHERE 过滤字段索引刷新完成", "count", count);
    }

    /**
     * 刷新业务口径（默认用内置 7 条；也可上传自定义 JSON 覆盖）。
     */
    @PostMapping("/refresh-metrics")
    public Map<String, Object> refreshMetrics(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "path", required = false) String path) throws IOException {
        byte[] bytes;
        if (file != null && !file.isEmpty()) {
            bytes = file.getBytes();
        } else if (path != null && !path.isBlank()) {
            bytes = Files.readAllBytes(Paths.get(path));
        } else {
            bytes = BusinessMetricsDefault.DEFAULT_JSON.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        int count = tableSchemaService.indexBusinessMetrics(bytes);
        return Map.of("message", "业务口径索引刷新完成", "count", count);
    }

    private byte[] readFileBytes(MultipartFile file, String path) {
        try {
            if (file != null && !file.isEmpty()) return file.getBytes();
            if (path != null && !path.isBlank()) return Files.readAllBytes(Paths.get(path));
            throw new RuntimeException("必须提供 file 或 path 参数");
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        }
    }

    // ==================== 辅助类（内部运维用）====================

    /**
     * 查询建议（前缀提示，类似百度下拉框）
     * GET /ai/sql/suggestions?prefix=稼动率&limit=10
     */
    @GetMapping("/suggestions")
    public Map<String, Object> suggestions(
            @RequestParam(defaultValue = "") String prefix,
            @RequestParam(defaultValue = "10") int limit) {
        return Map.of("prefix", prefix, "items", suggestionService.suggest(prefix, limit));
    }

    /**
     * 查询最近的审计日志（默认 50 条）
     */
    @GetMapping("/audit")
    public Map<String, Object> audit(@RequestParam(defaultValue = "50") int limit) {
        return Map.of("items", auditService.recent(limit));
    }

    /**
     * 查询 Schema 版本快照
     */
    @GetMapping("/schema-snapshots")
    public Map<String, Object> snapshots(@RequestParam(defaultValue = "20") int limit) {
        return Map.of("items", schemaRegistry.recent(limit));
    }
}
