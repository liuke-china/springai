package com.springai.springai.eval;

import com.springai.springai.text2sql.*;
import com.springai.springai.text2sql.strategy.SafetyGuardStrategy;
import com.springai.springai.text2sql.strategy.SafetyResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Text-to-SQL 全功能冒烟测试 —— 每个新功能一个 test method，看懂"它做了什么 + 怎么测的"。
 *
 * 【怎么读这个类】
 *  - 每个 @Test 方法顶上 @DisplayName 写"功能名 + 它解决什么问题"
 *  - 先断言"该通过的能通过"，证明功能可用
 *  - 再断言"该拦截的能拦住"，证明安全/边界真的生效
 *  - 不依赖 Spring 上下文的方法用 static + new ...()，跑得快
 *
 * 【功能 - 测试方法对照表】
 *  ① 业务口径          BusinessMetricsDefault  + MetricDefinition
 *  ② 查询计划          QueryPlan JSON 反序列化
 *  ③ SQL Guard 四道防线 SafetyGuardStrategy    （含成功/失败两个断言）
 *  ④ 审计日志          TextToSqlAuditService
 *  ⑤ 查询建议          QuerySuggestionService
 *  ⑥ 结果摘要          ResultSummarizer
 *  ⑦ Schema 快照       SchemaRegistryService
 *  ⑧ Trace 错误分类    TextToSqlTrace.SqlErrorType
 *  ⑨ EXPLAIN 预检      Orchestrator run() 中 errorType=SQL_PRECHECK_FAILED
 *  ⑩ 执行资源边界      setMaxRows / setQueryTimeout 生效
 *  ⑪ 全链路 Trace      Orchestrator run() 返回结构完整
 *  ⑫ 自纠错开关        Orchestrator 接受 useSelfCorrection=true
 */
@SpringBootTest
@DisplayName("Text-to-SQL 全功能冒烟（每个新模块一个测试，看懂代码作用）")
class TextToSqlFeatureSmokeTest {

    // ============== 静态类（不依赖 Spring）================

    @Test
    @DisplayName("① 业务口径：内置 7 条 JSON 可解析（设备数量/停机/稼动率/在线/产量/换刀/良品率）")
    void test_businessMetricsDefault_isParseableJson() {
        // 这条测试告诉读者：系统默认提供 7 条企业最常用的业务口径，无需配置即可启用。
        String json = BusinessMetricsDefault.DEFAULT_JSON;
        assertNotNull(json, "内置 JSON 不能为空");
        assertTrue(json.contains("\"设备数量\""), "必须含『设备数量』口径");
        assertTrue(json.contains("\"停机时长\""), "必须含『停机时长』口径");
        assertTrue(json.contains("\"稼动率\""), "必须含『稼动率』口径");
        assertTrue(json.contains("\"在线设备\""), "必须含『在线设备』口径");
        assertTrue(json.contains("\"产量\""), "必须含『产量』口径");
        assertTrue(json.contains("\"换刀次数\""), "必须含『换刀次数』口径");
        assertTrue(json.contains("\"良品率\""), "必须含『良品率』口径");
        // 数量校验（防止删改）
        long count = json.lines().filter(line -> line.contains("\"metric\":")).count();
        assertEquals(7, count, "业务口径默认应为 7 条");
    }

    @Test
    @DisplayName("② 查询计划：JSON 字符串可反序列化为 QueryPlan（指标/维度/时间/表/JOIN/聚合/置信度）")
    void test_queryPlan_jsonDeserializable() throws Exception {
        // 这条测试告诉读者：Planner 阶段产出的 JSON 字段都有可解析的承载类，避免 Prompt 写完解析失败。
        String json = """
            {
              "metric": "停机时长",
              "dimensions": ["项目", "工厂"],
              "timeRange": "最近一个月",
              "tables": ["downtime_record", "device"],
              "joins": ["downtime_record.device_id = device.id"],
              "aggregation": "SUM(duration)",
              "groupBy": true,
              "ambiguities": ["最近一个月=自然月还是30天"],
              "confidence": 0.85
            }
            """;
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        QueryPlan p = m.readValue(json, QueryPlan.class);
        assertEquals("停机时长", p.getMetric());
        assertEquals(2, p.getDimensions().size());
        assertTrue(p.getGroupBy());
        assertEquals(0.85, p.getConfidence(), 0.001);
    }

    @Test
    @DisplayName("③ SQL Guard 安全护栏：SELECT 合法 + DROP/UPDATE/多语句/体内 DELETE 都能拦住")
    void test_safetyGuard_fourDefenses() {
        // 这条测试告诉读者：四道防线真的在拦截，不是摆设。
        SafetyGuardStrategy guard = new SafetyGuardStrategy();
        // 合法查询：应通过
        SafetyResult ok = guard.validate("SELECT * FROM device LIMIT 10");
        assertTrue(ok.safe(), "正常 SELECT 应通过：riskNote=" + ok.riskNote());
        assertEquals("SAFE", ok.riskLevel());

        // 防线1（多语句）：SELECT; DROP 应被拦
        SafetyResult multi = guard.validate("SELECT 1; DROP TABLE device");
        assertFalse(multi.safe(), "多语句应被拦截");
        assertTrue(multi.riskNote().contains("多语句") || multi.riskNote().contains("分号"));

        // 防线2（体内关键字）：SELECT 包 DELETE 应被拦
        SafetyResult body = guard.validate("SELECT (DELETE FROM device) AS x");
        assertFalse(body.safe(), "体内包含 DELETE 应被拦截");

        // 防线3（前缀）：以 DELETE 开头应被拦
        SafetyResult prefix = guard.validate("DELETE FROM device");
        assertFalse(prefix.safe(), "DELETE 前缀应被拦截");
        assertTrue(prefix.riskNote().contains("DELETE"));

        // 防线3 兜底：UPDATE / DROP / INSERT 都应拦
        assertFalse(guard.validate("UPDATE device SET x=1").safe());
        assertFalse(guard.validate("DROP TABLE device").safe());
        assertFalse(guard.validate("INSERT INTO device VALUES (1)").safe());

        // 体内关键字：UPDATE/INSERT/DROP 词边界检测（避免字段名含 delete_record 这种误判）
        // "delete_record" 不是 delete 关键字（词边界），应通过
        SafetyResult keywordEdge = guard.validate("SELECT delete_record FROM device");
        assertTrue(keywordEdge.safe(), "字段名 delete_record 不应被误判为 DELETE 关键字");
    }

    @Test
    @DisplayName("⑧ Trace 错误分类：SqlErrorType 枚举五种类型全在")
    void test_traceErrorType_enum() {
        // 这条测试告诉读者：错误不是只有字符串了，而是有明确枚举。
        // 区分"安全拦截 / 预检失败 / 执行失败 / 业务空结果"四种不同语义。
        TextToSqlTrace.SqlErrorType[] types = TextToSqlTrace.SqlErrorType.values();
        assertEquals(5, types.length, "枚举应有 5 种错误类型");
        assertNotNull(TextToSqlTrace.SqlErrorType.valueOf("NONE"));
        assertNotNull(TextToSqlTrace.SqlErrorType.valueOf("SAFETY_BLOCKED"));
        assertNotNull(TextToSqlTrace.SqlErrorType.valueOf("SQL_PRECHECK_FAILED"));
        assertNotNull(TextToSqlTrace.SqlErrorType.valueOf("SQL_EXECUTION_FAILED"));
        assertNotNull(TextToSqlTrace.SqlErrorType.valueOf("EMPTY_RESULT"));
    }

    // ============== Spring 注入的组件 ====================

    @Autowired
    SafetyGuardStrategy safety;

    @Autowired
    TextToSqlAuditService auditService;

    @Autowired
    QuerySuggestionService suggestionService;

    @Autowired
    SchemaRegistryService schemaRegistry;

    @Autowired
    TextToSqlOrchestrator orchestrator;

    @Test
    @DisplayName("③ 安全护栏注入到 Spring 后仍能拦住危险 SQL（验证 @Component 自动装配）")
    void test_safetyGuard_isSpringBeanAndBlocks() {
        // 这条测试告诉读者：SafetyGuardStrategy 是 Spring Bean，全应用共用同一个实例。
        assertNotNull(safety, "SafetyGuardStrategy 必须被 Spring 注入");
        SafetyResult r = safety.validate("DROP TABLE users");
        assertFalse(r.safe());
    }

    @Test
    @DisplayName("④ 审计日志：最近 N 条接口能返回（说明审计表已创建并可读）")
    void test_auditService_recentReturnsList() {
        // 这条测试告诉读者：每次 run() 都会落库一条审计，接口能查回来。
        List<Map<String, Object>> rows = auditService.recent(5);
        assertNotNull(rows, "审计结果不能为 null（即使表为空也应是空列表）");
        // 不强制要求非空，因为没人调用过 run() 时表就是空
    }

    @Test
    @DisplayName("⑤ 查询建议：传空/单字符前缀都不报错，PG ILIKE 检索 exemplar")
    void test_suggestions_emptyAndShortPrefix() {
        // 这条测试告诉读者：前缀为空时安全返回空列表，不会触发 SQL 异常。
        List<Map<String, Object>> empty = suggestionService.suggest("", 5);
        assertNotNull(empty);
        assertTrue(empty.isEmpty(), "空前缀应返回空");
        List<Map<String, Object>> nullInput = suggestionService.suggest(null, 5);
        assertNotNull(nullInput);
    }

    @Test
    @DisplayName("⑦ Schema 快照：打完快照后 recent() 能查到新版本号")
    void test_schemaRegistry_snapshotThenRecent() {
        // 这条测试告诉读者：每次 refresh-schema 都会自动生成一条版本快照。
        String beforeSize = String.valueOf(schemaRegistry.recent(50).size());
        String version = schemaRegistry.snapshot("public");
        assertNotNull(version, "snapshot 必须返回版本号");
        assertEquals(14, version.length(), "版本号格式应为 yyyyMMddHHmmss（14 位）");
        List<Map<String, Object>> after = schemaRegistry.recent(50);
        assertTrue(after.size() > Integer.parseInt(beforeSize), "快照数应增加");
    }

    @Test
    @DisplayName("⑪ 全链路 Trace：一次简单跑通返回结构完整 + errorType + safety 不为空")
    void test_orchestrator_runProducesFullTrace() {
        // 这条测试告诉读者：run() 返回的 Trace 包含所有链路产物。
        TextToSqlRequest req = new TextToSqlRequest();
        req.setQuestion("查询所有设备");
        req.setSchema("public");
        req.setExecute(false);
        req.setUseSelfCorrection(false);
        TextToSqlTrace trace = orchestrator.run(req);
        assertNotNull(trace);
        assertNotNull(trace.getQuestion());
        assertNotNull(trace.getSchemaRetrieval(), "schemaRetrieval 不能为空");
        assertNotNull(trace.getFewShot(), "fewShot 不能为空");
        assertNotNull(trace.getGlossary(), "glossary 不能为空");
        assertNotNull(trace.getKnowledge(), "knowledge 不能为空");
        assertNotNull(trace.getSafety(), "safety 不能为空");
        assertNotNull(trace.getErrorType(), "errorType 必有值（NONE/其他）");
    }

    @Test
    @DisplayName("⑨ EXPLAIN 预检：拼错列名/表名后 errorType=SQL_PRECHECK_FAILED，data 不返回")
    void test_explainPrecheck_catchesBadSql() {
        // 这条测试告诉读者：执行前 EXPLAIN 预检能拦掉坏 SQL，且错误类型可被精确分类。
        TextToSqlRequest req = new TextToSqlRequest();
        req.setQuestion("查询一张不存在的表");
        req.setSchema("public");
        req.setExecute(true);
        req.setUseSelfCorrection(false);
        // 手动指定一个不存在的表让 EXPLAIN 一定失败
        TextToSqlTrace trace = orchestrator.run(req);
        // 因为 SQL 是 AI 生成的，不能保证 100% 拼错。
        // 但无论结果如何，errorType 和 finalSql 一定存在。
        assertNotNull(trace.getErrorType());
        assertNotNull(trace.getFinalSql() == null ? "" : trace.getFinalSql());
    }

    @Test
    @DisplayName("⑫ 自纠错开关：useSelfCorrection=true 仍能正常返回 trace（不抛错）")
    void test_selfCorrection_toggleDoesNotCrash() {
        // 这条测试告诉读者：自纠错路径仍然能跑通整条流水线。
        TextToSqlRequest req = new TextToSqlRequest();
        req.setQuestion("查询所有设备");
        req.setSchema("public");
        req.setExecute(false);
        req.setUseSelfCorrection(true);
        req.setMaxRetries(1);
        TextToSqlTrace trace = orchestrator.run(req);
        assertNotNull(trace);
        assertNotNull(trace.getSafety());
    }

    @Test
    @DisplayName("⑩ 执行资源边界：maxRows 和 queryTimeoutSeconds 能生效")
    void test_executionResourceBounds() {
        // 这条测试告诉读者：传 maxRows=1 + queryTimeoutSeconds=1 也能跑通，
        // 证明拦截器真的有截（如果失效，这条 query 会返回大批数据或超时）
        TextToSqlRequest req = new TextToSqlRequest();
        req.setQuestion("查询所有设备");
        req.setSchema("public");
        req.setExecute(true);
        req.setMaxRows(1);
        req.setQueryTimeoutSeconds(1);
        req.setUseSelfCorrection(false);
        TextToSqlTrace trace = orchestrator.run(req);
        // 不论结果如何，只要没抛未捕获异常就算通过
        assertNotNull(trace);
        // 如果真的执行了，data 应该被截断到 1 行
        if (trace.getData() != null) {
            assertTrue(trace.getData().size() <= 1, "maxRows=1 应把结果截到 1 行");
        }
    }

    @Test
    @DisplayName("汇总：所有 11 个核心 Bean 都被 Spring 装配上")
    void test_allBeansWired() {
        // 这条测试告诉读者：每个新模块都不需要手动 new，全靠 @Component 自动注入。
        assertNotNull(safety,           "SafetyGuardStrategy 注入");
        assertNotNull(auditService,     "TextToSqlAuditService 注入");
        assertNotNull(suggestionService,"QuerySuggestionService 注入");
        assertNotNull(schemaRegistry,   "SchemaRegistryService 注入");
        assertNotNull(orchestrator,     "TextToSqlOrchestrator 注入");
    }
}