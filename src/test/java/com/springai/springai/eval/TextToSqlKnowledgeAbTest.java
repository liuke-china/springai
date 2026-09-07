package com.springai.springai.eval;

import com.springai.springai.smalldemo.entity.SqlResult;
import com.springai.springai.smalldemo.service.TableSchemaService;
import com.springai.springai.text2sql.TextToSqlOrchestrator;
import com.springai.springai.text2sql.TextToSqlRequest;
import com.springai.springai.text2sql.TextToSqlTrace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Text-to-SQL 知识增强 A/B 评测（v2）。
 *
 * 目标：用同一组固定问题，对比两种配置：
 *   A_OFF：仅靠表结构召回（useExemplar=false, useGlossary=false, useKnowledge=false）
 *   B_ON ：表结构 + few-shot + glossary + 已知 JOIN/接口知识（全部开启）
 *   两者 useCot=false, useSelfCorrection=false — 锁定单一变量，避免 CoT 干扰知识效果。
 *
 * 测量三类指标：
 *
 *   第一类：可执行的硬指标（机器判定，不需要 LLM 自评）
 *     - safeRate            SQL 通过 SafetyGuard 的比例
 *     - executableRate      SQL 在真实 PostgreSQL 成功执行的比例
 *     - nonEmptyRate        成功执行且返回至少一行的比例（仅作数据可用性参考，不能等同正确率）
 *     - averageLatencyMs    端到端耗时
 *     - 平均 few-shot / glossary / foreign_key / interface_map 命中数
 *
 *   第二类：业务正确性指标（依据 IMS 真实表结构、FK JSON、interface_map JSON 硬编码期望）
 *     - tableRecall         生成 SQL 覆盖 expectedTables 的比例
 *     - keywordRecall       生成 SQL 覆盖 expectedKeywords 的比例
 *     - joinCorrectness     requiredJoins 在最终 SQL 中全部命中的比例
 *                          （用启发式匹配：列名 + 表名同时出现即视为该 JOIN 命中；
 *                            完全无 requiredJoins 的单表题记为 N/A）
 *     - interfaceRecall     B_ON 下，命中的 interface_map 覆盖 expectedMappers / expectedTables 的加权命中度
 *                          （A_OFF 不走接口映射检索，记 0 但在汇总标注）
 *     - businessCorrectRate 同时满足：safe + executable + tableRecall==1 + keywordRecall>=阈值
 *                                  + (无 requiredJoins 或 joinCorrectness==1)
 *                                  + (无 expectedMappers 或 interfaceRecall>=阈值)
 *     ⚠ 空结果 ≠ 业务错误；本评测以 SQL 结构正确为主，不把"数据为空"直接判错。
 *     ⚠ "SQL 可执行" ≠ "业务正确"，必须看 tableRecall / joinCorrectness / businessCorrectRate。
 *
 *   第三类：每题对照（OFF vs ON）
 *     - 同一 question、同 schema、同 PG，分别跑两轮，对比 SQL 差异、命中表、命中 JOIN。
 *
 * 运行：mvn test -Dtest=TextToSqlKnowledgeAbTest
 * 输出：
 *   target/text2sql-eval/text2sql_ab_<时间戳>.csv        每条记录一行（按 id × variant 拆分）
 *   target/text2sql-eval/text2sql_ab_summary_<时间戳>.csv  按题目汇总 OFF/ON 两行
 *
 * 注意：
 *   - 不会重写生产业务代码，不会修改 Orchestrator/Controller；只新增/改造测试类。
 *   - 不会删除或重灌 vector_store 数据。
 *   - 不会让 LLM 自评自身生成的 SQL。
 *   - 不会因为单题执行失败而中断整个测试；失败记入 CSV，继续跑下一题。
 *   - 为规避 LLM/embedding 上游 hang，单题用 ExecutorService 强制 90 秒超时；超时记 timeout，继续下一题。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TextToSqlKnowledgeAbTest {

    private static final Logger log = LoggerFactory.getLogger(TextToSqlKnowledgeAbTest.class);

    private static final String SCHEMA = "public";

    /** 单题超时（秒）。LLM/embedding 上游 hang 时强制中断。 */
    private static final long PER_CASE_TIMEOUT_SECONDS = 90L;

    /**
     * 表结构向量在生产���境是 @EventListener(ApplicationReadyEvent) 异步初始化的。
     * SpringBootTest 启动后测试方法可能立刻跑，导致 schema 还没建好（表召回空）。
     * 测试前轮询 vector_store 直到 5 类全到位，避免把"启动未就绪"算成业务失败。
     */
    @BeforeAll
    void waitForKnowledgeBaseReady(@Autowired JdbcTemplate jdbcTemplate,
                                   @Autowired TableSchemaService tableSchemaService) {
        String sql = "SELECT coalesce(metadata->>'type','(null)') AS t, count(*) FROM vector_store GROUP BY 1";
        java.util.function.BooleanSupplier ready = () -> {
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
                java.util.Map<String, Integer> m = new java.util.HashMap<>();
                for (Map<String, Object> r : rows) {
                    m.put(String.valueOf(r.get("t")), ((Number) r.get("j")).intValue());
                }
                return m.getOrDefault("table_schema", 0) >= 200
                        && m.getOrDefault("glossary", 0) >= 1000
                        && m.getOrDefault("exemplar", 0) >= 1000
                        && m.getOrDefault("foreign_key", 0) >= 100
                        && m.getOrDefault("interface_map", 0) >= 100;
            } catch (Exception e) {
                log.warn("轮询向量库失败: {}", e.getMessage());
                return false;
            }
        };

        long deadline = System.currentTimeMillis() + 180_000L; // 最长 3 分钟
        while (System.currentTimeMillis() < deadline && !ready.getAsBoolean()) {
            try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        if (!ready.getAsBoolean()) {
            log.warn("向量库未在 3 分钟内就绪，主动调用 indexSchema 兜底（embedding 失败时不会抛错）");
            try { tableSchemaService.indexSchema(SCHEMA); } catch (Exception e) {
                log.error("主动 indexSchema 仍失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 评测题目（精简 10 题：原 10 题里挑 5 + JOIN 5）。
     *
     * 题量精简是为了在 LLM 上游/embedding 不稳定时仍能在合理时间内跑完全部 A/B 对照；
     * 用户可在环境稳定后把全部 25 题重新加入。
     * 每题 expectedTables / expectedKeywords / requiredJoins / expectedMappers /
     * expectedInterface 均来自 IMS 真实代码：
     *   - ims_foreign_keys.json（high-confidence JOIN 证据）
     *   - ims_interface_map.json（菜单 → Mapper / 表）
     *   - PostgreSQL 真实表结构（information_schema.columns）
     *   - MyBatis XML 中出现过的 JOIN 子句（ForeignKey.json 里 evidence 字段）
     */
    private static final List<EvalCase> CASES = List.of(
            // ============ 基线 5 题 ============
            new EvalCase("Q02", "各状态设备分别有多少台？",
                    List.of("device"), List.of("state", "count"), List.of(),
                    List.of(), null, "SINGLE_TABLE", "easy"),
            new EvalCase("Q04", "查询设备停机原因和停机时长，并显示设备名称。",
                    List.of("downtime_record", "reason", "device"),
                    List.of("reason", "duration", "device_name"),
                    List.of(
                            new JoinSpec("downtime_record", "reason_id", "reason", "id"),
                            new JoinSpec("downtime_record", "device_id", "device", "id")
                    ),
                    List.of("DowntimeRecordMapper", "ReasonMapper", "DeviceMapper"),
                    null, "JOIN_3TABLE", "medium"),
            new EvalCase("Q05", "哪些停机记录还没有填写停机原因？",
                    List.of("downtime_record"),
                    List.of("reason_id"),
                    List.of(),
                    List.of("DowntimeRecordMapper"),
                    null, "SINGLE_TABLE", "easy"),
            new EvalCase("Q08", "按项目和制程统计设备数量。",
                    List.of("device_level"),
                    List.of("project_name", "process_name", "count"),
                    List.of(),
                    List.of(), null, "AGG", "medium"),
            new EvalCase("Q10", "查询最近的设备停机记录，关联停机原因名称。",
                    List.of("downtime_record", "reason"),
                    List.of("reason"),
                    List.of(new JoinSpec("downtime_record", "reason_id", "reason", "id")),
                    List.of("DowntimeRecordMapper", "ReasonMapper"),
                    null, "JOIN_2TABLE", "easy"),

            // ============ JOIN 专项 5 题 ============
            new EvalCase("J01", "列出设备停机记录及停机原因名称、设备名称和停机时长。",
                    List.of("downtime_record", "reason", "device"),
                    List.of("reason", "duration", "device_name"),
                    List.of(
                            new JoinSpec("downtime_record", "reason_id", "reason", "id"),
                            new JoinSpec("downtime_record", "device_id", "device", "id")
                    ),
                    List.of("DowntimeRecordMapper", "ReasonMapper", "DeviceMapper"),
                    null, "JOIN_3TABLE", "medium"),
            new EvalCase("J02", "查询设备时间轴记录以及对应的停机原因名称。",
                    List.of("device_operation_report", "downtime_record", "reason"),
                    List.of("device_operation_report", "reason"),
                    List.of(
                            new JoinSpec("downtime_record", "device_operation_report_id", "device_operation_report", "id"),
                            new JoinSpec("downtime_record", "reason_id", "reason", "id")
                    ),
                    List.of("DeviceMapper", "DowntimeRecordMapper", "ReasonMapper"),
                    null, "JOIN_3TABLE", "medium"),
            new EvalCase("J03", "查询设备对应的工厂、项目、制程、单元和工位名称。",
                    List.of("device", "device_level"),
                    List.of("factoty_name", "project_name", "process_name", "cell_name", "station_name"),
                    List.of(new JoinSpec("device_level", "machine_id", "device", "id")),
                    List.of("DeviceMapper", "AreaMapper"),
                    null, "JOIN_2TABLE", "medium"),
            new EvalCase("J04", "查询换刀记录及对应的刀具供应商名称。",
                    List.of("tool_exchange", "tool_supplier"),
                    List.of("brand_name", "supplier_name"),
                    List.of(new JoinSpec("tool_exchange", "brand_id", "tool_supplier", "id")),
                    List.of("ToolExchangeMapper", "ToolSupplierMapper"),
                    null, "JOIN_2TABLE", "easy"),
            new EvalCase("J05", "按工厂、项目、制程统计各设备的停机时长，并显示停机原因名称。",
                    List.of("downtime_record", "device", "device_level", "reason"),
                    List.of("factoty_name", "project_name", "process_name", "duration", "reason"),
                    List.of(
                            new JoinSpec("downtime_record", "device_id", "device", "id"),
                            new JoinSpec("device_level", "machine_id", "device", "id"),
                            new JoinSpec("downtime_record", "reason_id", "reason", "id")
                    ),
                    List.of("DowntimeRecordMapper", "DeviceMapper", "AreaMapper", "ReasonMapper"),
                    null, "JOIN_4TABLE", "hard")
    );

    @Autowired
    private TextToSqlOrchestrator orchestrator;

    @Test
    void compareKnowledgeOffVsOn() throws Exception {
        List<RunResult> results = new ArrayList<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ab-case-runner");
            t.setDaemon(true);
            return t;
        });

        for (EvalCase evalCase : CASES) {
            RunResult off = runWithTimeout(executor, () -> runOnce(evalCase, "A_OFF", false, false, false),
                    evalCase.id(), "A_OFF");
            RunResult on  = runWithTimeout(executor, () -> runOnce(evalCase, "B_ON", true, true, true),
                    evalCase.id(), "B_ON");
            results.add(off);
            results.add(on);

            // 关键题（JOIN）逐题打印，方便快速观察
            if (evalCase.category().startsWith("JOIN")) {
                System.out.printf(Locale.ROOT,
                        "[%s %s] OFF.bc=%s ON.bc=%s | OFF.tabR=%.2f ON.tabR=%.2f | OFF.join=%.2f ON.join=%.2f%n",
                        evalCase.id(), evalCase.category(),
                        off.businessCorrect(), on.businessCorrect(),
                        off.tableRecall(), on.tableRecall(),
                        Double.isNaN(off.joinCorrectness()) ? -1 : off.joinCorrectness(),
                        Double.isNaN(on.joinCorrectness()) ? -1 : on.joinCorrectness());
            }
        }

        executor.shutdownNow();

        printSummary(results, "A_OFF");
        printSummary(results, "B_ON");
        printPerCaseDelta(results);

        Path detailCsv = writeDetailCsv(results);
        Path summaryCsv = writeSummaryCsv(results);
        System.out.println("\n明细 CSV: " + detailCsv.toAbsolutePath());
        System.out.println("汇总 CSV: " + summaryCsv.toAbsolutePath());

        assertEquals(CASES.size() * 2, results.size(), "每题必须跑 OFF/ON 两组");
    }

    private RunResult runWithTimeout(ExecutorService executor, Callable<RunResult> task,
                                     String caseId, String variant) {
        long start = System.nanoTime();
        Future<RunResult> f = executor.submit(task);
        try {
            return f.get(PER_CASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            f.cancel(true);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("[{} {}] 单题超时（{}s），记失败继续", caseId, variant, PER_CASE_TIMEOUT_SECONDS);
            return new RunResult(null, variant, false, false, false, 0, elapsedMs,
                    "", "TIMEOUT after " + PER_CASE_TIMEOUT_SECONDS + "s", 0, 0, 0, 0,
                    0.0, 0.0, 0.0, 0.0, false);
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            return new RunResult(null, variant, false, false, false, 0, elapsedMs,
                    "", e.getClass().getSimpleName() + ": " + nullToEmpty(e.getMessage()),
                    0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, false);
        }
    }

    private RunResult runOnce(EvalCase evalCase, String variant,
                              boolean useExemplar, boolean useGlossary, boolean useKnowledge) {
        TextToSqlRequest req = new TextToSqlRequest();
        req.setQuestion(evalCase.question());
        req.setSchema(SCHEMA);
        req.setUseExemplar(useExemplar);
        req.setUseGlossary(useGlossary);
        req.setUseKnowledge(useKnowledge);
        req.setUseCot(false);
        req.setUseSelfCorrection(false);
        req.setExecute(true);

        long start = System.nanoTime();
        try {
            TextToSqlTrace trace = orchestrator.run(req);
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            SqlResult sqlResult = trace.getSqlResult();
            String sql = sqlResult == null ? "" : nullToEmpty(sqlResult.getSql());
            boolean safe = trace.getSafety() != null && trace.getSafety().safe();
            boolean executable = safe && isBlank(trace.getExecuteError()) && !sql.isBlank();
            int rowCount = trace.getData() == null ? 0 : trace.getData().size();
            boolean nonEmpty = rowCount > 0;

            int fewShotHits = countFewShot(trace);
            int glossaryHits = countGlossary(trace);
            int fkHits = countForeignKeys(trace);
            int imHits = countInterfaceMaps(trace);

            Set<String> sqlTokens = tokenize(sql);
            double tableRecall = tableRecall(evalCase.expectedTables(), sqlTokens);
            double keywordRecall = keywordRecall(evalCase.expectedKeywords(), sql, sqlTokens);
            double joinCorrectness = joinCorrectness(evalCase.requiredJoins(), sql);
            double interfaceRecall = interfaceRecall(
                    evalCase.expectedMappers(), evalCase.expectedTables(),
                    trace.getKnowledge() == null ? null : trace.getKnowledge().matchedInterfaceMaps(),
                    variant);

            boolean businessCorrect = computeBusinessCorrect(
                    safe, executable, tableRecall, keywordRecall,
                    joinCorrectness, interfaceRecall, evalCase);

            return new RunResult(evalCase, variant,
                    safe, executable, nonEmpty, rowCount, latencyMs,
                    sql, nullToEmpty(trace.getExecuteError()),
                    fewShotHits, glossaryHits, fkHits, imHits,
                    tableRecall, keywordRecall, joinCorrectness, interfaceRecall,
                    businessCorrect);
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            return new RunResult(evalCase, variant,
                    false, false, false, 0, latencyMs,
                    "", e.getClass().getSimpleName() + ": " + nullToEmpty(e.getMessage()),
                    0, 0, 0, 0,
                    0.0, 0.0, 0.0, 0.0,
                    false);
        }
    }

    // ---------------- 命中指标实现 ----------------

    private static Set<String> tokenize(String sql) {
        if (sql == null || sql.isBlank()) return Collections.emptySet();
        String lower = sql.toLowerCase();
        Matcher m = Pattern.compile("[a-z_][a-z0-9_]*").matcher(lower);
        Set<String> out = new LinkedHashSet<>();
        while (m.find()) {
            String t = m.group();
            if (t.length() < 2) continue;
            if (isSqlKeyword(t)) continue;
            out.add(t);
        }
        return out;
    }

    private static final Set<String> SQL_KEYWORDS = Set.of(
            "select", "from", "where", "and", "or", "not", "null", "is", "in",
            "join", "left", "right", "inner", "outer", "full", "on", "as",
            "group", "by", "order", "asc", "desc", "having", "limit", "offset",
            "union", "all", "distinct", "case", "when", "then", "else", "end",
            "count", "sum", "avg", "min", "max", "between", "like", "ilike",
            "true", "false", "int", "bigint", "varchar", "char", "text",
            "timestamp", "date", "time", "interval", "numeric", "boolean",
            "current_date", "current_timestamp"
    );

    private static boolean isSqlKeyword(String t) {
        return SQL_KEYWORDS.contains(t);
    }

    private static double tableRecall(List<String> expectedTables, Set<String> sqlTokens) {
        if (expectedTables == null || expectedTables.isEmpty()) return 1.0;
        long hits = 0;
        for (String t : expectedTables) {
            if (sqlTokens.contains(t.toLowerCase())) hits++;
        }
        return (double) hits / expectedTables.size();
    }

    private static double keywordRecall(List<String> expectedKeywords, String sql, Set<String> sqlTokens) {
        if (expectedKeywords == null || expectedKeywords.isEmpty()) return 1.0;
        if (sql == null || sql.isBlank()) return 0.0;
        String lower = sql.toLowerCase();
        long hits = 0;
        for (String k : expectedKeywords) {
            String key = k.toLowerCase();
            if (lower.contains(key) || sqlTokens.contains(key)) hits++;
        }
        return (double) hits / expectedKeywords.size();
    }

    private static double joinCorrectness(List<JoinSpec> required, String sql) {
        if (required == null || required.isEmpty()) return -1.0;
        if (sql == null || sql.isBlank()) return 0.0;
        String lower = sql.toLowerCase();
        Set<String> tokens = new LinkedHashSet<>();
        Matcher m = Pattern.compile("[a-z_][a-z0-9_]*").matcher(lower);
        while (m.find()) tokens.add(m.group());

        long hits = 0;
        for (JoinSpec j : required) {
            boolean fromCol = tokens.contains(j.fromColumn().toLowerCase()) || lower.contains(j.fromColumn().toLowerCase());
            boolean toCol = tokens.contains(j.toColumn().toLowerCase()) || lower.contains(j.toColumn().toLowerCase());
            boolean fromTab = tokens.contains(j.fromTable().toLowerCase());
            boolean toTab = tokens.contains(j.toTable().toLowerCase());
            if (fromCol && toCol && (fromTab || toTab)) {
                hits++;
            }
        }
        return (double) hits / required.size();
    }

    private static double interfaceRecall(List<String> expectedMappers,
                                          List<String> expectedTables,
                                          List<Map<String, Object>> matchedInterfaceMaps,
                                          String variant) {
        if (expectedMappers == null || expectedMappers.isEmpty()) return 1.0;
        if (!"B_ON".equals(variant)) return 0.0;
        if (matchedInterfaceMaps == null || matchedInterfaceMaps.isEmpty()) return 0.0;

        Set<String> hitMappers = new LinkedHashSet<>();
        Set<String> hitTables = new LinkedHashSet<>();
        for (Map<String, Object> im : matchedInterfaceMaps) {
            Object m = im.get("mappers");
            if (m instanceof String) hitMappers.addAll(Arrays.asList(((String) m).split(",")));
            else if (m instanceof List) {
                for (Object o : (List<?>) m) if (o != null) hitMappers.add(String.valueOf(o).trim());
            }
            Object t = im.get("tables");
            if (t instanceof String) hitTables.addAll(Arrays.asList(((String) t).split(",")));
            else if (t instanceof List) {
                for (Object o : (List<?>) t) if (o != null) hitTables.add(String.valueOf(o).trim());
            }
        }

        long mapperHits = countHits(expectedMappers, hitMappers);
        double mapperRecall = expectedMappers.isEmpty() ? 1.0 : (double) mapperHits / expectedMappers.size();

        long tableHits = expectedTables == null ? 0 : countHits(expectedTables, hitTables);
        double tableRecall = (expectedTables == null || expectedTables.isEmpty())
                ? 1.0 : (double) tableHits / expectedTables.size();

        return 0.5 * mapperRecall + 0.5 * tableRecall;
    }

    private static long countHits(List<String> expected, Set<String> actualLowerOrExact) {
        long hits = 0;
        for (String e : expected) {
            if (actualLowerOrExact.contains(e)) { hits++; continue; }
            for (String a : actualLowerOrExact) {
                if (a != null && a.equalsIgnoreCase(e)) { hits++; break; }
            }
        }
        return hits;
    }

    private static boolean computeBusinessCorrect(boolean safe, boolean executable,
                                                  double tableRecall, double keywordRecall,
                                                  double joinCorrectness, double interfaceRecall,
                                                  EvalCase evalCase) {
        if (!safe || !executable) return false;
        if (tableRecall < 1.0) return false;
        if (keywordRecall < 0.5) return false;
        if (evalCase.requiredJoins() != null && !evalCase.requiredJoins().isEmpty()
                && joinCorrectness < 1.0) return false;
        if (evalCase.expectedMappers() != null && !evalCase.expectedMappers().isEmpty()
                && interfaceRecall < 0.3) return false;
        return true;
    }

    // ---------------- 汇总 / 输出 ----------------

    private static void printSummary(List<RunResult> results, String variant) {
        List<RunResult> group = results.stream().filter(r -> variant.equals(r.variant())).toList();
        long safe = group.stream().filter(RunResult::safe).count();
        long executable = group.stream().filter(RunResult::executable).count();
        long nonEmpty = group.stream().filter(RunResult::nonEmpty).count();
        long bc = group.stream().filter(RunResult::businessCorrect).count();
        double avgLatency = group.stream().mapToLong(RunResult::latencyMs).average().orElse(0D);
        double avgFewShot = group.stream().mapToInt(RunResult::fewShotHits).average().orElse(0D);
        double avgGlossary = group.stream().mapToInt(RunResult::glossaryHits).average().orElse(0D);
        double avgFk = group.stream().mapToInt(RunResult::foreignKeyHits).average().orElse(0D);
        double avgInterface = group.stream().mapToInt(RunResult::interfaceMapHits).average().orElse(0D);
        double avgTable = group.stream().mapToDouble(RunResult::tableRecall).average().orElse(0D);
        double avgKeyword = group.stream().mapToDouble(RunResult::keywordRecall).average().orElse(0D);

        List<RunResult> joinEligible = group.stream().filter(r -> r.joinCorrectness() >= 0).toList();
        double avgJoin = joinEligible.isEmpty() ? -1
                : joinEligible.stream().mapToDouble(RunResult::joinCorrectness).average().orElse(0D);

        List<RunResult> interfaceEligible = group.stream()
                .filter(r -> r.evalCase() != null && r.evalCase().expectedMappers() != null
                        && !r.evalCase().expectedMappers().isEmpty())
                .toList();
        double avgInterfaceRecall = interfaceEligible.isEmpty() ? -1
                : interfaceEligible.stream().mapToDouble(RunResult::interfaceRecall).average().orElse(0D);

        System.out.printf(Locale.ROOT, """
                ========== %s 汇总 ==========
                safeRate:           %.1f%% (%d/%d)
                executableRate:     %.1f%% (%d/%d)
                nonEmptyRate:       %.1f%% (%d/%d)
                businessCorrectRate:%.1f%% (%d/%d)
                tableRecall:        %.2f
                keywordRecall:      %.2f
                joinCorrectness:    %s
                interfaceRecall:    %s%s
                avgLatencyMs:       %.0f
                avg few-shot/glossary/FK/interface hits: %.1f / %.1f / %.1f / %.1f
                ==================================
                """, variant,
                percent(safe, group.size()), safe, group.size(),
                percent(executable, group.size()), executable, group.size(),
                percent(nonEmpty, group.size()), nonEmpty, group.size(),
                percent(bc, group.size()), bc, group.size(),
                avgTable, avgKeyword,
                avgJoin < 0 ? "N/A" : String.format(Locale.ROOT, "%.2f", avgJoin),
                avgInterfaceRecall < 0 ? "N/A" : String.format(Locale.ROOT, "%.2f", avgInterfaceRecall),
                "B_ON".equals(variant) ? "" : "  (A_OFF 不召回 interface_map)",
                avgLatency, avgFewShot, avgGlossary, avgFk, avgInterface);
    }

    private static void printPerCaseDelta(List<RunResult> results) {
        System.out.println("\n========== 每题 OFF vs ON 差异 ==========");
        java.util.Map<String, List<RunResult>> byId = new java.util.LinkedHashMap<>();
        for (RunResult r : results) {
            byId.computeIfAbsent(r.evalCase().id(), k -> new ArrayList<>()).add(r);
        }
        for (java.util.Map.Entry<String, List<RunResult>> e : byId.entrySet()) {
            RunResult off = e.getValue().stream().filter(r -> "A_OFF".equals(r.variant())).findFirst().orElse(null);
            RunResult on = e.getValue().stream().filter(r -> "B_ON".equals(r.variant())).findFirst().orElse(null);
            if (off == null || on == null) continue;
            boolean sqlDiff = !safeEq(off.sql(), on.sql());
            boolean bcDiff = off.businessCorrect() != on.businessCorrect();
            if (sqlDiff || bcDiff) {
                System.out.printf(Locale.ROOT, "[%s] bc OFF=%s → ON=%s | SQL changed: %s%n",
                        e.getKey(), off.businessCorrect(), on.businessCorrect(), sqlDiff ? "Y" : "N");
                if (sqlDiff) {
                    String oSql = off.sql() == null ? "" : off.sql();
                    String nSql = on.sql() == null ? "" : on.sql();
                    System.out.printf(Locale.ROOT, "  OFF: %s%n  ON : %s%n", trim(oSql, 240), trim(nSql, 240));
                }
            } else {
                System.out.printf(Locale.ROOT, "[%s] 两组 SQL 一致 & bc 相同 (%s)%n",
                        e.getKey(), off.businessCorrect());
            }
        }
    }

    private static boolean safeEq(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.equals(b);
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static Path writeDetailCsv(List<RunResult> results) throws IOException {
        Path dir = Path.of("target", "text2sql-eval");
        Files.createDirectories(dir);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path file = dir.resolve("text2sql_ab_" + ts + ".csv");
        StringBuilder csv = new StringBuilder();
        csv.append("id,variant,category,difficulty,question,safe,executable,non_empty,row_count,latency_ms,")
                .append("few_shot_hits,glossary_hits,foreign_key_hits,interface_map_hits,")
                .append("expected_tables,expected_keywords,required_joins,expected_mappers,expected_interface,")
                .append("table_recall,keyword_recall,join_correctness,interface_recall,business_correct,")
                .append("execute_error,sql\n");
        for (RunResult r : results) {
            String id = r.evalCase() == null ? "(timeout)" : r.evalCase().id();
            String cat = r.evalCase() == null ? "" : r.evalCase().category();
            String diff = r.evalCase() == null ? "" : r.evalCase().difficulty();
            String q = r.evalCase() == null ? "" : r.evalCase().question();
            String et = r.evalCase() == null ? "" : joinList(r.evalCase().expectedTables());
            String ek = r.evalCase() == null ? "" : joinList(r.evalCase().expectedKeywords());
            String rj = r.evalCase() == null ? "" : joinJoins(r.evalCase().requiredJoins());
            String em = r.evalCase() == null ? "" : joinList(r.evalCase().expectedMappers());
            String ei = r.evalCase() == null ? "" : nullToEmpty(r.evalCase().expectedInterface());
            csv.append(csv(id)).append(',')
                    .append(csv(r.variant())).append(',')
                    .append(csv(cat)).append(',')
                    .append(csv(diff)).append(',')
                    .append(csv(q)).append(',')
                    .append(r.safe()).append(',')
                    .append(r.executable()).append(',')
                    .append(r.nonEmpty()).append(',')
                    .append(r.rowCount()).append(',')
                    .append(r.latencyMs()).append(',')
                    .append(r.fewShotHits()).append(',')
                    .append(r.glossaryHits()).append(',')
                    .append(r.foreignKeyHits()).append(',')
                    .append(r.interfaceMapHits()).append(',')
                    .append(csv(et)).append(',')
                    .append(csv(ek)).append(',')
                    .append(csv(rj)).append(',')
                    .append(csv(em)).append(',')
                    .append(csv(ei)).append(',')
                    .append(formatNum(r.tableRecall())).append(',')
                    .append(formatNum(r.keywordRecall())).append(',')
                    .append(formatNum(r.joinCorrectness())).append(',')
                    .append(formatNum(r.interfaceRecall())).append(',')
                    .append(r.businessCorrect()).append(',')
                    .append(csv(r.executeError())).append(',')
                    .append(csv(r.sql())).append('\n');
        }
        Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static Path writeSummaryCsv(List<RunResult> results) throws IOException {
        Path dir = Path.of("target", "text2sql-eval");
        Files.createDirectories(dir);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path file = dir.resolve("text2sql_ab_summary_" + ts + ".csv");

        java.util.Map<String, List<RunResult>> byId = new java.util.LinkedHashMap<>();
        for (RunResult r : results) {
            String id = r.evalCase() == null ? "TIMEOUT" : r.evalCase().id();
            byId.computeIfAbsent(id, k -> new ArrayList<>()).add(r);
        }

        StringBuilder csv = new StringBuilder();
        csv.append("id,category,difficulty,question,")
                .append("off_safe,off_executable,off_table_recall,off_keyword_recall,off_join_correctness,off_interface_recall,off_business_correct,off_sql,")
                .append("on_safe,on_executable,on_table_recall,on_keyword_recall,on_join_correctness,on_interface_recall,on_business_correct,on_sql,")
                .append("bc_delta_off_to_on,off_failed_reason,on_failed_reason\n");
        for (java.util.Map.Entry<String, List<RunResult>> e : byId.entrySet()) {
            RunResult off = e.getValue().stream().filter(r -> "A_OFF".equals(r.variant())).findFirst().orElse(null);
            RunResult on = e.getValue().stream().filter(r -> "B_ON".equals(r.variant())).findFirst().orElse(null);
            if (off == null || on == null) continue;
            int delta = (on.businessCorrect() ? 1 : 0) - (off.businessCorrect() ? 1 : 0);
            String id = off.evalCase() == null ? e.getKey() : off.evalCase().id();
            String cat = off.evalCase() == null ? "" : off.evalCase().category();
            String diff = off.evalCase() == null ? "" : off.evalCase().difficulty();
            String q = off.evalCase() == null ? "" : off.evalCase().question();
            csv.append(csv(id)).append(',')
                    .append(csv(cat)).append(',')
                    .append(csv(diff)).append(',')
                    .append(csv(q)).append(',')
                    .append(off.safe()).append(',')
                    .append(off.executable()).append(',')
                    .append(formatNum(off.tableRecall())).append(',')
                    .append(formatNum(off.keywordRecall())).append(',')
                    .append(formatNum(off.joinCorrectness())).append(',')
                    .append(formatNum(off.interfaceRecall())).append(',')
                    .append(off.businessCorrect()).append(',')
                    .append(csv(off.sql())).append(',')
                    .append(on.safe()).append(',')
                    .append(on.executable()).append(',')
                    .append(formatNum(on.tableRecall())).append(',')
                    .append(formatNum(on.keywordRecall())).append(',')
                    .append(formatNum(on.joinCorrectness())).append(',')
                    .append(formatNum(on.interfaceRecall())).append(',')
                    .append(on.businessCorrect()).append(',')
                    .append(csv(on.sql())).append(',')
                    .append(delta).append(',')
                    .append(csv(failureReason(off))).append(',')
                    .append(csv(failureReason(on))).append('\n');
        }
        Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static String failureReason(RunResult r) {
        if (r.businessCorrect()) return "";
        if (!r.safe()) return "unsafe";
        if (!r.executable()) return "exec_failed: " + r.executeError();
        if (r.tableRecall() < 1.0) return "missing_tables";
        if (r.keywordRecall() < 0.5) return "missing_keywords";
        if (Double.isNaN(r.joinCorrectness())) return "join_missing";
        if (r.joinCorrectness() < 1.0) return "join_wrong";
        if (r.interfaceRecall() < 0.3) return "interface_missing";
        return "unknown";
    }

    private static String joinList(List<String> list) {
        if (list == null || list.isEmpty()) return "";
        return String.join("|", list);
    }

    private static String joinJoins(List<JoinSpec> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (JoinSpec j : list) {
            if (sb.length() > 0) sb.append("|");
            sb.append(j.fromTable()).append('.').append(j.fromColumn())
                    .append('=').append(j.toTable()).append('.').append(j.toColumn());
        }
        return sb.toString();
    }

    private static String formatNum(double v) {
        if (Double.isNaN(v) || v < 0) return "N/A";
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static String csv(String value) {
        return '"' + nullToEmpty(value).replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + '"';
    }

    private static double percent(long numerator, long denominator) {
        return denominator == 0 ? 0D : numerator * 100D / denominator;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int countFewShot(TextToSqlTrace trace) {
        return trace.getFewShot() == null || trace.getFewShot().matchedExemplars() == null
                ? 0 : trace.getFewShot().matchedExemplars().size();
    }

    private static int countGlossary(TextToSqlTrace trace) {
        return trace.getGlossary() == null || trace.getGlossary().matchedTerms() == null
                ? 0 : trace.getGlossary().matchedTerms().size();
    }

    private static int countForeignKeys(TextToSqlTrace trace) {
        return trace.getKnowledge() == null || trace.getKnowledge().matchedForeignKeys() == null
                ? 0 : trace.getKnowledge().matchedForeignKeys().size();
    }

    private static int countInterfaceMaps(TextToSqlTrace trace) {
        return trace.getKnowledge() == null || trace.getKnowledge().matchedInterfaceMaps() == null
                ? 0 : trace.getKnowledge().matchedInterfaceMaps().size();
    }

    // =============== 题目定义 ===============

    public record JoinSpec(String fromTable, String fromColumn, String toTable, String toColumn) {
    }

    public record EvalCase(
            String id,
            String question,
            List<String> expectedTables,
            List<String> expectedKeywords,
            List<JoinSpec> requiredJoins,
            List<String> expectedMappers,
            String expectedInterface,
            String category,
            String difficulty
    ) {
    }

    private record RunResult(
            EvalCase evalCase,
            String variant,
            boolean safe,
            boolean executable,
            boolean nonEmpty,
            int rowCount,
            long latencyMs,
            String sql,
            String executeError,
            int fewShotHits,
            int glossaryHits,
            int foreignKeyHits,
            int interfaceMapHits,
            double tableRecall,
            double keywordRecall,
            double joinCorrectness,
            double interfaceRecall,
            boolean businessCorrect
    ) {
    }
}