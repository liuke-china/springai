package com.springai.springai.smalldemo.service;

import com.springai.springai.text2sql.Exemplar;
import com.springai.springai.text2sql.ForeignKey;
import com.springai.springai.text2sql.Glossary;
import com.springai.springai.text2sql.InterfaceMap;
import com.springai.springai.text2sql.MetricDefinition;
import com.springai.springai.text2sql.WhereHint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 表结构向量化服务
 *
 * 核心作用：将数据库表结构存入 PGVector，用户提问时先用 Embedding 检索相关表，
 * 再把相关表结构注入 Prompt，避免 100+ 张表全部塞进 Prompt 导致 AI 被干扰和响应慢。
 *
 * 【工作流程】
 * 1. 应用启动时（@EventListener），异步将所有表结构向量化存入 PGVector
 * 2. 每个表生成一条描述文本："表名: device_info, 字段: id(主键), name(设备名称), ..."
 * 3. 用户提问时，用 Embedding 搜索最相关的 N 张表
 * 4. 只把这 N 张表的详细结构从 information_schema 读取出来，注入 Prompt
 */
@Service
public class TableSchemaService {

    private static final Logger log = LoggerFactory.getLogger(TableSchemaService.class);

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 每次检索返回的相关表数量（可通过 application.yml 配置）
     */
    @Value("${text-to-sql.default-topk:10}")
    private int defaultTopK;

    public TableSchemaService(VectorStore vectorStore, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 应用启动完成后自动初始化表结构向量索引（异步，不阻塞启动）
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        CompletableFuture.runAsync(() -> {
            try {
                indexSchema("public");
            } catch (Exception e) {
                log.error("表结构向量化初始化失败，请稍后调用 POST /ai/sql/refresh-schema 手动初始化。原因: {}", e.getMessage());
            }
        });
    }

    /**
     * 将指定 schema 的表结构向量化存入 PGVector
     *
     * 【实现思路】
     * 1. 从 information_schema 读取所有表名和字段信息
     * 2. 为每个表生成一段语义描述文本（包含表名+字段名+注释）
     * 3. 以 UUID 作为文档 ID（PGVector 要求 ID 必须是 UUID 格式）
     * 4. 元数据标记 type=table_schema + tableName，与 RAG 文档区分
     *
     * @param schema 数据库 schema 名
     */
    public void indexSchema(String schema) {
        log.info("开始索引 schema [{}] 的表结构到向量库...", schema);

        // 1. 读取所有表名
        String tableNamesSql = """
                SELECT DISTINCT table_name
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name NOT IN ('vector_store', 'pg_stat_statements')
                ORDER BY table_name
                """;
        List<String> tableNames = jdbcTemplate.queryForList(tableNamesSql, schema)
                .stream().map(row -> (String) row.get("table_name"))
                .collect(Collectors.toList());

        if (tableNames.isEmpty()) {
            log.info("schema [{}] 中没有找到表", schema);
            return;
        }

        // 1.5 过滤空表：一张数据都没有的表不进向量库，避免 AI 被空表干扰、选错表。
        //      用「SELECT 1 ... LIMIT 1」判断是否有数据（大表也能瞬间返回），非空才保留。
        List<String> keepTables = new ArrayList<>();
        int skippedEmpty = 0;
        for (String t : tableNames) {
            if (isEmptyTable(schema, t)) {
                skippedEmpty++;
            } else {
                keepTables.add(t);
            }
        }
        Set<String> keepSet = new HashSet<>(keepTables);

        // 2. 清除旧的表结构文档（PGVector 不支持按 metadata 删除，用 JDBC 直接删除）
        try {
            jdbcTemplate.update(
                    "DELETE FROM vector_store WHERE metadata->>'type' = 'table_schema'");
            log.info("已清除旧的表结构向量数据");
        } catch (Exception e) {
            log.warn("清除旧表结构文档时出现警告: {}", e.getMessage());
        }

        // 3. 读取完整的列信息
        String columnsSql = """
                SELECT c.table_name, c.column_name, c.data_type,
                       col_description((quote_ident(c.table_schema) || '.' || quote_ident(c.table_name))::regclass, c.ordinal_position) AS column_comment
                FROM information_schema.columns c
                WHERE c.table_schema = ?
                  AND c.table_name NOT IN ('vector_store', 'pg_stat_statements')
                ORDER BY c.table_name, c.ordinal_position
                """;
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(columnsSql, schema);

        // 4. 按表名分组，为每个表生成一段语义描述文本
        Map<String, List<Map<String, Object>>> tableMap = columns.stream()
                .collect(Collectors.groupingBy(col -> (String) col.get("table_name")));

        List<Document> documents = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : tableMap.entrySet()) {
            String tableName = entry.getKey();
            // 空表已在上面判定，这里再兜底过滤一次（columns 来自全表，须排除空表）
            if (!keepSet.contains(tableName)) continue;
            List<Map<String, Object>> cols = entry.getValue();

            StringBuilder desc = new StringBuilder();
            desc.append("表名: ").append(tableName).append(", ");
            desc.append("字段: ");
            for (int i = 0; i < cols.size(); i++) {
                Map<String, Object> col = cols.get(i);
                String comment = (String) col.get("column_comment");
                if (comment != null && !comment.isBlank()) {
                    desc.append(col.get("column_name")).append("(").append(comment).append(")");
                } else {
                    desc.append(col.get("column_name"));
                }
                if (i < cols.size() - 1) {
                    desc.append(", ");
                }
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("type", "table_schema");
            metadata.put("tableName", tableName);
            metadata.put("columnCount", cols.size());

            // PGVector 要求 ID 必须是 UUID 格式
            String docId = UUID.randomUUID().toString();
            documents.add(new Document(docId, desc.toString(), metadata));
        }

        // 5. 批量存入向量库（PGVector 会自动调用 Embedding 模型生成向量）
        vectorStore.add(documents);
        log.info("表结构向量化完成，共索引 {} 张表（schema: {}），跳过空表 {} 张", documents.size(), schema, skippedEmpty);
    }

    /**
     * 判断某张表是否为空（0 行）。
     *
     * 【为什么用 SELECT 1 ... LIMIT 1 而不是 count(*)】
     * count(*) 会对全表扫描计数，遇到 downtime_record 这种近 800 万行的表会很慢；
     * SELECT 1 LIMIT 1 只要找到第一行就返回，空表返回 0 行、非空表瞬间返回 1 行。
     *
     * 【异常处理】
     * 表名含特殊字符（不走安全校验）或查询报错时，保守返回 false —— 当作"有数据"保留进向量库，
     * 宁可多留一张，也不误删一张真有数据的表。
     *
     * @param schema    schema 名
     * @param tableName 表名
     * @return true=空表（无数据），false=有数据
     */
    private boolean isEmptyTable(String schema, String tableName) {
        // 只允许标准标识符，杜绝任何 SQL 注入可能（表名来自 information_schema，本应安全，这里再兜一层）
        if (tableName == null || !tableName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return false;
        }
        String sql = "SELECT 1 FROM " + schema + "." + tableName + " LIMIT 1";
        try {
            Integer one = jdbcTemplate.queryForObject(sql, Integer.class);
            return one == null;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 将 NL2SQL few-shot 范例（问题+SQL 对）向量化存入 PGVector。
     *
     * 【与 indexSchema 的关系】
     * 共用同一 vectorStore 通道（PGVector 自动用 bge-m3 嵌 1024 维），
     * 但 metadata.type='exemplar' 与表结构(type='table_schema')、RAG 文档区分开。
     *
     * 【JSON 结构】
     * 顶层 {"exemplars":[{ "id","mapper","tables":[...],"category","difficulty",
     *                       "intent","question","sql","sql_raw" }, ...]}
     * content = 自然语言问题（用于被相似度检索命中）；
     * metadata 携带 type/mapper/tables/category/difficulty/sql/intent。
     *
     * @param jsonBytes 范例 JSON 文件内容（字节数组，由 controller 从上传文件或路径读取后传入）
     * @return 成功写入的文档数量
     */
    public int indexExemplars(byte[] jsonBytes) {
        try {
            Exemplar.ExemplarFile wrapper =
                    objectMapper.readValue(jsonBytes, Exemplar.ExemplarFile.class);
            List<Exemplar> list = wrapper.getExemplars();
            if (list == null || list.isEmpty()) {
                log.warn("范例 JSON 中没有任何 exemplars 条目，跳过索引");
                return 0;
            }

            // 1. 清除旧范例（PGVector 不支持按 metadata 删除，用 JDBC 直接删）
            jdbcTemplate.update(
                    "DELETE FROM vector_store WHERE metadata->>'type' = 'exemplar'");

            // 2. 逐条构建 Document
            List<Document> docs = new ArrayList<>();
            for (Exemplar ex : list) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("type", "exemplar");
                meta.put("mapper", ex.getMapper());
                meta.put("tables",
                        ex.getTables() == null ? "" : String.join(",", ex.getTables()));
                meta.put("category", ex.getCategory());
                meta.put("difficulty", ex.getDifficulty());
                meta.put("sql", ex.getSql());
                meta.put("intent", ex.getIntent());

                // PGVector 要求 ID 必须是 UUID 格式；content = 自然语言问题
                docs.add(new Document(
                        UUID.randomUUID().toString(), ex.getQuestion(), meta));
            }

            // 3. 批量存入向量库（自动生成 Embedding）
            vectorStore.add(docs);
            log.info("范例向量化完成，共索引 {} 条 (type=exemplar)", docs.size());
            return docs.size();
        } catch (Exception e) {
            log.error("范例向量化失败: {}", e.getMessage());
            throw new RuntimeException("范例向量化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 根据用户问题检索相关的 NL2SQL 范例（few-shot 候选）
     *
     * 【与 findRelevantTables 对称】
     * 复用同一 vectorStore 相似度搜索，过滤 type='exemplar'，取语义最相近的 topK 条。
     * content=自然语言问题，metadata.sql=对应干净 SQL，metadata.intent=业务意图。
     *
     * @param question 用户问题
     * @return 相关范例 Document 列表（按相似度排序），未初始化时为空
     */
    public List<Document> findRelevantExemplars(String question) {
        return findRelevantExemplars(question, defaultTopK);
    }

    public List<Document> findRelevantExemplars(String question, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(question)
                .topK(topK * 3)
                .build();
        List<Document> results = vectorStore.similaritySearch(request);
        List<Document> exemplars = results.stream()
                .filter(doc -> "exemplar".equals(doc.getMetadata().get("type")))
                .limit(topK)
                .collect(Collectors.toList());
        if (exemplars.isEmpty()) {
            log.warn("向量库中未找到相关范例，请确认已调用 POST /ai/sql/refresh-exemplars 初始化");
        } else {
            log.info("问题「{}」匹配到 {} 条相关范例", question, exemplars.size());
        }
        return exemplars;
    }

    /**
     * 根据用户问题检索相关的表名（核心方法）
     *
     * 【原理】
     * 用 Embedding 将用户问题转向量，在 PGVector 中做相似度搜索，
     * 返回语义最相关的表名列表。例如用户问"查询设备在线状态"，
     * 会匹配到 device_info、device_status 等表。
     *
     * @param question 用户问题
     * @return 相关表名列表，按相似度排序
     */
    public List<String> findRelevantTables(String question) {
        return findRelevantTables(question, defaultTopK);
    }

    public List<String> findRelevantTables(String question, int topK) {
        // 多请求一些（topK * 3），因为向量库中可能混有非表结构文档（如 RAG 文档）

        SearchRequest request = SearchRequest.builder()
                .query(question)
                .topK(topK * 3)
                .build();
        List<Document> results = vectorStore.similaritySearch(request);

        // 过滤出表结构文档，按相似度排序取 topK
        List<String> tableNames = results.stream()
                .filter(doc -> "table_schema".equals(doc.getMetadata().get("type")))
                .map(doc -> (String) doc.getMetadata().get("tableName"))
                .distinct()
                .limit(topK)
                .collect(Collectors.toList());

        if (tableNames.isEmpty()) {
            log.warn("向量库中未找到相关表结构，请确认已调用 POST /ai/sql/refresh-schema 初始化");
        } else {
            log.info("问题「{}」匹配到 {} 张相关表: {}", question, tableNames.size(), tableNames);
        }

        return tableNames;
    }

    /**
     * 读取指定表的完整结构（从 information_schema 实时读取，保证数据最新）
     *
     * @param schema     schema 名
     * @param tableNames 要读取的表名列表
     * @return 格式化的表结构字符串（注入到 AI Prompt 中）
     */
    public String readTableSchema(String schema, List<String> tableNames) {
        if (tableNames == null || tableNames.isEmpty()) {
            return "";
        }

        String placeholders = tableNames.stream().map(t -> "?").collect(Collectors.joining(","));

        String sql = """
                SELECT c.table_name, c.column_name, c.data_type,
                       col_description((quote_ident(c.table_schema) || '.' || quote_ident(c.table_name))::regclass, c.ordinal_position) AS column_comment
                FROM information_schema.columns c
                WHERE c.table_schema = ?
                  AND c.table_name IN (%s)
                ORDER BY c.table_name, c.ordinal_position
                """.formatted(placeholders);

        List<Object> params = new ArrayList<>();
        params.add(schema);
        params.addAll(tableNames);

        List<Map<String, Object>> columns = jdbcTemplate.queryForList(sql, params.toArray());

        // 按表名分组，生成类似 DDL 的格式
        Map<String, List<Map<String, Object>>> tableMap = columns.stream()
                .collect(Collectors.groupingBy(col -> (String) col.get("table_name")));

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<Map<String, Object>>> entry : tableMap.entrySet()) {
            sb.append("表: ").append(entry.getKey()).append("\n");
            for (Map<String, Object> col : entry.getValue()) {
                String comment = (String) col.get("column_comment");
                String commentStr = (comment != null && !comment.isBlank())
                        ? " -- " + comment : "";
                sb.append("  ").append(col.get("column_name"))
                        .append(" (").append(col.get("data_type")).append(")")
                        .append(commentStr).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 读取指定 schema 的全部表结构（向量库无命中时的回退方案）。
     *
     * @param schema schema 名
     * @return 格式化的全表结构字符串
     */
    public String readTableSchema(String schema) {
        String sql = """
                SELECT c.table_name, c.column_name, c.data_type,
                       col_description((quote_ident(c.table_schema) || '.' || quote_ident(c.table_name))::regclass, c.ordinal_position) AS column_comment
                FROM information_schema.columns c
                WHERE c.table_schema = ?
                  AND c.table_name NOT IN ('vector_store', 'pg_stat_statements')
                ORDER BY c.table_name, c.ordinal_position
                """;
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(sql, schema);

        Map<String, List<Map<String, Object>>> tableMap = columns.stream()
                .collect(Collectors.groupingBy(col -> (String) col.get("table_name")));

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<Map<String, Object>>> entry : tableMap.entrySet()) {
            sb.append("表: ").append(entry.getKey()).append("\n");
            for (Map<String, Object> col : entry.getValue()) {
                String comment = (String) col.get("column_comment");
                String commentStr = (comment != null && !comment.isBlank()) ? " -- " + comment : "";
                sb.append("  ").append(col.get("column_name"))
                        .append(" (").append(col.get("data_type")).append(")")
                        .append(commentStr).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 把业务术语 / 字段枚举字典向量化存入 PGVector（type='glossary'）。
     *
     * @param jsonBytes 字典 JSON 文件内容（字节数组）
     * @return 成功写入的文档数量
     */
    public int indexGlossary(byte[] jsonBytes) {
        try {
            Glossary wrapper = objectMapper.readValue(jsonBytes, Glossary.class);
            List<Glossary.GlossaryItem> list = wrapper.getItems();
            if (list == null || list.isEmpty()) {
                log.warn("术语字典 JSON 中没有任何条目，跳过索引");
                return 0;
            }

            jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'type' = 'glossary'");

            List<Document> docs = new ArrayList<>();
            for (Glossary.GlossaryItem item : list) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("type", "glossary");
                meta.put("term", item.getTerm());
                meta.put("definition", item.getDefinition());
                meta.put("tables", item.getTables());
                meta.put("fields", item.getFields());
                meta.put("synonyms", item.getSynonyms());

                // content = 术语 + 定义 + 同义词（用于语义检索命中）；metadata 携带结构化字段
                String content = String.join(" ",
                        of(item.getTerm()), of(item.getDefinition()), of(item.getSynonyms()));
                docs.add(new Document(UUID.randomUUID().toString(), content, meta));
            }

            vectorStore.add(docs);
            log.info("术语字典向量化完成，共索引 {} 条 (type=glossary)", docs.size());
            return docs.size();
        } catch (Exception e) {
            log.error("术语字典向量化失败: {}", e.getMessage());
            throw new RuntimeException("术语字典向量化失败: " + e.getMessage(), e);
        }
    }

    private static String of(String s) {
        return (s == null) ? "" : s;
    }

    /**
     * 把业务口径（metric_definition）向量化存入 PGVector（type='business_metric'）。
     *
     * 【为什么需要】
     * AI 生成 SQL 经常"能跑但答错"，根因是不知道业务口径：设备数量=COUNT(*) 还是 COUNT(DISTINCT device.id)？
     * 停机时长是否排除未结束记录？把口径显式入库，提问时按语义检索注入 Prompt，AI 就能照搬标准表达式。
     *
     * @param jsonBytes 业务口径 JSON
     * @return 成功索引条数
     */
    public int indexBusinessMetrics(byte[] jsonBytes) {
        try {
            MetricDefinition.MetricFile wrapper =
                    objectMapper.readValue(jsonBytes, MetricDefinition.MetricFile.class);
            List<MetricDefinition> list = wrapper.getMetrics();
            if (list == null || list.isEmpty()) {
                log.warn("业务口径 JSON 中没有任何条目，跳过索引");
                return 0;
            }
            jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'type' = 'business_metric'");
            List<Document> docs = new ArrayList<>();
            for (MetricDefinition m : list) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("type", "business_metric");
                meta.put("metric", m.getMetric());
                meta.put("definition", m.getDefinition());
                meta.put("expression", m.getExpression());
                meta.put("grain", m.getGrain());
                meta.put("tables", m.getTables());
                meta.put("source", m.getSource());
                meta.put("confidence", m.getConfidence());

                // content = 指标 + 同义词 + 定义（让语义检索命中），结构化字段放 metadata
                StringBuilder content = new StringBuilder();
                content.append(of(m.getMetric())).append(" ");
                if (m.getSynonyms() != null) content.append(String.join(" ", m.getSynonyms())).append(" ");
                content.append(of(m.getDefinition())).append(" ");
                content.append(of(m.getExpression()));
                docs.add(new Document(UUID.randomUUID().toString(), content.toString().trim(), meta));
            }
            vectorStore.add(docs);
            log.info("业务口径向量化完成，共索引 {} 条 (type=business_metric)", docs.size());
            return docs.size();
        } catch (Exception e) {
            log.error("业务口径向量化失败: {}", e.getMessage());
            throw new RuntimeException("业务口径向量化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 把表关联关系向量化存入 PGVector（type='foreign_key'）。
     *
     * 对应 text2sql-refs/ims_foreign_keys.json。每条 = IMS 项目里实际出现过的一次跨表关联
     * （来自 MyBatis XML JOIN / resultMap / 命名约定），提问时按语义检索注入 Prompt，
     * 告诉 AI "项目里这些表就该这么 JOIN"，防止它凭直觉拼错 JOIN 条件或漏掉关键关联。
     *
     * @param jsonBytes 表关联 JSON 文件内容
     * @return 成功写入的文档数量
     */
    public int indexForeignKeys(byte[] jsonBytes) {
        try {
            ForeignKey.ForeignKeyFile wrapper = objectMapper.readValue(jsonBytes, ForeignKey.ForeignKeyFile.class);
            List<ForeignKey> list = wrapper.getForeignKeys();
            if (list == null || list.isEmpty()) {
                log.warn("表关联 JSON 中没有任何条目，跳过索引");
                return 0;
            }

            jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'type' = 'foreign_key'");

            List<Document> docs = new ArrayList<>();
            for (ForeignKey fk : list) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("type", "foreign_key");
                meta.put("from_table", fk.getFromTable());
                meta.put("from_column", fk.getFromColumn());
                meta.put("to_table", fk.getToTable());
                meta.put("to_column", fk.getToColumn());
                meta.put("confidence", fk.getConfidence());
                meta.put("evidence", fk.getEvidence());

                // content 拼成自然语言短句，给 bge-m3 嵌入；joinHint 单放 metadata 便于 SQL 生成时直接复制
                String content = String.join(" ",
                        of(fk.getFromTable()),
                        of(fk.getFromColumn()),
                        "通过 JOIN 关联到",
                        of(fk.getToTable()),
                        of(fk.getToColumn()),
                        "依据",
                        of(fk.getSource()));
                meta.put("join_hint", fk.getJoinHint());
                docs.add(new Document(UUID.randomUUID().toString(), content, meta));
            }

            vectorStore.add(docs);
            log.info("表关联向量化完成，共索引 {} 条 (type=foreign_key)", docs.size());
            return docs.size();
        } catch (Exception e) {
            log.error("表关联向量化失败: {}", e.getMessage());
            throw new RuntimeException("表关联向量化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 把前端菜单 / 后端接口 → 物理表映射向量化存入 PGVector（type='interface_map'）。
     *
     * 对应 text2sql-refs/ims_interface_map.json。每条 = 一个菜单/按钮追溯到后端 mapper
     * 真实查询过的物理表。提问时按语义检索注入 Prompt，帮助 AI 理解"用户问的功能
     * 可能对应哪个菜单，背后是哪些表"，提升召回准确率。
     *
     * @param jsonBytes 接口映射 JSON 文件内容
     * @return 成功写入的文档数量
     */
    public int indexInterfaceMap(byte[] jsonBytes) {
        try {
            InterfaceMap.InterfaceMapFile wrapper = objectMapper.readValue(jsonBytes, InterfaceMap.InterfaceMapFile.class);
            List<InterfaceMap> list = wrapper.getInterfaces();
            if (list == null || list.isEmpty()) {
                log.warn("接口映射 JSON 中没有任何条目，跳过索引");
                return 0;
            }

            jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'type' = 'interface_map'");

            List<Document> docs = new ArrayList<>();
            for (InterfaceMap im : list) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("type", "interface_map");
                meta.put("menu_id", im.getMenuId());
                meta.put("menu_name", im.getMenuName());
                meta.put("perms", im.getPerms());
                meta.put("mappers", im.getMappers());

                List<String> tableNames = new ArrayList<>();
                if (im.getTables() != null) {
                    for (InterfaceMap.TableRef tr : im.getTables()) {
                        tableNames.add(tr.getTable());
                    }
                }
                meta.put("tables", tableNames);

                String content = String.join(" ",
                        of(im.getMenuName()),
                        "菜单",
                        of(im.getRoutePath()),
                        "对应 mappers",
                        of(String.join(",", im.getMappers() == null ? List.of() : im.getMappers())),
                        "涉及表",
                        of(String.join(",", tableNames)));
                docs.add(new Document(UUID.randomUUID().toString(), content, meta));
            }

            vectorStore.add(docs);
            log.info("接口映射向量化完成，共索引 {} 条 (type=interface_map)", docs.size());
            return docs.size();
        } catch (Exception e) {
            log.error("接口映射向量化失败: {}", e.getMessage());
            throw new RuntimeException("接口映射向量化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 把「各表常用 WHERE 过滤字段（来自 IMS Mapper 源码）」向量化存入 PGVector（type='where_hint'）。
     *
     * 对应 scripts/validate/where_hints.json（由 extract_where_hints.py 从 MyBatis XML 确定性抽取）。
     * 每条 = 一张物理表在源码真实查询里最常用的过滤字段 + 运算 + 频次，外加时间区间模式提示。
     * 提问时由 WhereHintStrategy 按 schema 召回出的表名精确拉取，注入 Prompt 作为生成 WHERE 的参考先验。
     *
     * @param jsonBytes where_hints.json 文件内容
     * @return 成功写入的文档数量
     */
    public int indexWhereHints(byte[] jsonBytes) {
        try {
            WhereHint.WhereHintFile wrapper =
                    objectMapper.readValue(jsonBytes, WhereHint.WhereHintFile.class);
            List<WhereHint.WhereHintItem> list = wrapper.getItems();
            if (list == null || list.isEmpty()) {
                log.warn("where_hint JSON 中没有任何条目，跳过索引");
                return 0;
            }

            jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'type' = 'where_hint'");

            List<Document> docs = new ArrayList<>();
            for (WhereHint.WhereHintItem it : list) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("type", "where_hint");
                meta.put("table", it.getTable());
                String content = it.getContent() == null ? "" : it.getContent();
                docs.add(new Document(UUID.randomUUID().toString(), content, meta));
            }

            vectorStore.add(docs);
            log.info("where_hint 向量化完成，共索引 {} 条 (type=where_hint)", docs.size());
            return docs.size();
        } catch (Exception e) {
            log.error("where_hint 向量化失败: {}", e.getMessage());
            throw new RuntimeException("where_hint 向量化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按表名精确拉取 where_hint（不依赖向量相似度，召回更准）。
     *
     * 【为什么按表名而不是按问题相似度】
     * where_hint 本质是"每表一条"的确定性先验；而 schema 召回阶段已经确定了相关问题涉及哪些表，
     * 直接用这些表名反查 where_hint，比再跑一次问题向量检索更精准、零歧义。
     *
     * @param tables schema 召回出的表名集合
     * @return 命中的 where_hint Document 列表（content 即可直接注入 Prompt 的文本）
     */
    public List<Document> findWhereHintsByTables(Collection<String> tables) {
        if (tables == null || tables.isEmpty()) return List.of();
        List<String> lower = tables.stream().map(String::toLowerCase).distinct().toList();
        try {
            String sql = "SELECT content, metadata->>'table' AS tbl " +
                    "FROM vector_store " +
                    "WHERE metadata->>'type' = 'where_hint' " +
                    "AND lower(metadata->>'table') = ANY(?::text[])";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, new Object[]{lower.toArray(new String[0])});
            List<Document> out = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                String content = r.get("content") == null ? "" : String.valueOf(r.get("content"));
                String tbl = r.get("tbl") == null ? "" : String.valueOf(r.get("tbl"));
                Map<String, Object> meta = new HashMap<>();
                meta.put("type", "where_hint");
                meta.put("table", tbl);
                out.add(new Document(UUID.randomUUID().toString(), content, meta));
            }
            return out;
        } catch (Exception e) {
            log.warn("按表拉取 where_hint 失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 按 metadata.type 过滤的语义检索（few-shot=exemplar / 字典=glossary 共用）。
     *
     * @param type     元数据类型（exemplar / glossary / table_schema）
     * @param question 用户问题
     * @param topK     返回条数
     * @return 命中的 Document 列表（按相似度排序）
     */
    public List<Document> findDocumentsByType(String type, String question, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(question)
                .topK(topK * 3)
                .build();
        List<Document> results = vectorStore.similaritySearch(request);
        List<Document> filtered = results.stream()
                .filter(doc -> type.equals(doc.getMetadata().get("type")))
                .limit(topK)
                .collect(Collectors.toList());
        if (filtered.isEmpty()) {
            log.warn("向量库中未找到 type={} 的相关文档", type);
        } else {
            log.info("问题「{}」匹配到 {} 条 type={} 文档", question, filtered.size(), type);
        }
        return filtered;
    }

    /**
     * 按"已选中的表名"精确关联检索 foreign_key / interface_map（不再靠问题语义相似度）。
     *
     * 【为什么不用 findDocumentsByType 的语义检索】
     * foreign_key 内容是"表A字段a JOIN 表B字段b"，与用户自然语言问题的语义距离远，
     * 纯语义相似度检索排不进 topK，导致关系知识有数据却捞不到、模型选表靠猜。
     * 正确做法：schema 召回已确定候选表，这里直接用候选表名去 metadata 里精确匹配
     * （foreign_key 的 from_table/to_table，interface_map 的 tables 数组），
     * 把"涉及这些表的所有真实 JOIN 关系"全部注入 Prompt。
     *
     * @param type    只支持 foreign_key / interface_map
     * @param tables  schema 召回的候选表名（小写匹配）
     * @param topK    上限
     */
    public List<Document> findKnowledgeByTables(String type, List<String> tables, int topK) {
        if (tables == null || tables.isEmpty()) return List.of();
        if (!"foreign_key".equals(type) && !"interface_map".equals(type)) return List.of();
        List<String> lower = tables.stream().map(String::toLowerCase).distinct().toList();
        try {
            String sql;
            if ("interface_map".equals(type)) {
                // tables 是 jsonb 数组，展开任一元素命中候选表即可
                sql = "SELECT content, metadata::text AS meta FROM vector_store "
                        + "WHERE metadata->>'type' = 'interface_map' "
                        + "AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(metadata->'tables') t "
                        + "            WHERE lower(t.value) = ANY(?::text[]))";
            } else {
                sql = "SELECT content, metadata::text AS meta FROM vector_store "
                        + "WHERE metadata->>'type' = 'foreign_key' "
                        + "AND (lower(metadata->>'from_table') = ANY(?::text[]) "
                        + "     OR lower(metadata->>'to_table') = ANY(?::text[]))";
            }
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, new Object[]{lower.toArray(new String[0])});
            List<Document> out = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                String content = r.get("content") == null ? "" : String.valueOf(r.get("content"));
                Map<String, Object> meta = parseMeta(r.get("meta"));
                out.add(new Document(UUID.randomUUID().toString(), content, meta));
                if (out.size() >= topK) break;
            }
            return out;
        } catch (Exception e) {
            log.warn("按表拉取 {} 失败: {}", type, e.getMessage());
            return List.of();
        }
    }

    /**
     * 根据已召回的候选表，找出同一条真实表关联关系中的另一张表。
     *
     * 例如候选表是 device_operation_report，而关系库记录它关联 device，
     * 这里就把 device 返回给上层，避免主表因语义相似度不够而漏掉。
     */
    public List<String> findRelatedTables(Collection<String> candidateTables) {
        if (candidateTables == null || candidateTables.isEmpty()) return List.of();
        List<String> lower = candidateTables.stream()
                .filter(Objects::nonNull)
                .map(String::toLowerCase)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
        if (lower.isEmpty()) return List.of();
        try {
            String sql = "SELECT metadata->>'from_table' AS from_table, "
                    + "metadata->>'to_table' AS to_table "
                    + "FROM vector_store "
                    + "WHERE metadata->>'type' = 'foreign_key' "
                    + "AND (lower(metadata->>'from_table') = ANY(?::text[]) "
                    + "     OR lower(metadata->>'to_table') = ANY(?::text[]))";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    sql, lower.toArray(new String[0]), lower.toArray(new String[0]));
            Set<String> candidates = new HashSet<>(lower);
            LinkedHashSet<String> related = new LinkedHashSet<>();
            for (Map<String, Object> row : rows) {
                addIfRelated(related, candidates, row.get("from_table"));
                addIfRelated(related, candidates, row.get("to_table"));
            }
            return List.copyOf(related);
        } catch (Exception e) {
            log.warn("按候选表补全关联表失败: {}", e.getMessage());
            return List.of();
        }
    }

    private void addIfRelated(Set<String> related, Set<String> candidates, Object value) {
        if (value == null) return;
        String table = String.valueOf(value).toLowerCase();
        if (!table.isBlank() && !candidates.contains(table)) related.add(table);
    }

    /** 把 vector_store 的 metadata 列（jsonb 文本或 Map）安全解析为 Map，供 Document 重建 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMeta(Object metaObj) {
        if (metaObj instanceof Map) return new HashMap<>((Map<String, Object>) metaObj);
        if (metaObj instanceof String s && !s.isBlank()) {
            try {
                return objectMapper.readValue(s, Map.class);
            } catch (Exception e) {
                log.warn("解析 metadata 失败: {}", e.getMessage());
            }
        }
        return new HashMap<>();
    }
}
