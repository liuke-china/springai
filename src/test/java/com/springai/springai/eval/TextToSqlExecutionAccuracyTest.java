package com.springai.springai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springai.springai.text2sql.TextToSqlOrchestrator;
import com.springai.springai.text2sql.TextToSqlRequest;
import com.springai.springai.text2sql.TextToSqlTrace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * L4 评测：执行准确率（Execution Accuracy, EX）—— 业界生产环境最常用、最硬的指标。
 *
 * 【一句话原理】
 * 准备一批「自然语言问题 + 标准答案 SQL（gold SQL）」的黄金集（golden set），
 * 让接口走【完整生产链路】生成 SQL，把「生成的 SQL」和「gold SQL」都丢数据库里跑，
 * 比对两边返回的结果集是否一致。一致才算对。
 *
 * 【为什么比之前的"可执行率/非空率"强】
 * 之前测的是"能不能出一条安全的、能跑出数据的 SQL"——只是及格线。
 * EX 测的是"跑出来的数据对不对"——这才是用户真正在乎的。
 * 例：问"设备有多少台"，模型返回 SELECT count(*) FROM device_operation_report（另一个表），
 *     非空率=100%（确实有数据），但 EX=0（数量根本不对）。
 *
 * 【结果集怎么比（关键点）】
 * 用「多集合（multiset）」比较：把每一行拍平成「排过序的值列表」，再把所有行排序后整体比较。
 * 这样：
 *   - 忽略行顺序（SELECT 没写 ORDER BY 时两边顺序可能不同）
 *   - 忽略列顺序/列名别名（gold 写 count(*) AS cnt，模型写 count(*) 也能判对）
 * 这正是 Spider/BIRD 里 EX 的宽松语义，避免"等价 SQL 被误杀"。
 *
 * 【黄金集怎么来的】
 * 标准基准（Spider/BIRD）是人标的；生产自建集用「混合」最稳：
 *   拿代码里真实存在的人写 SQL 当 gold SQL（可信），再用 AI 反向翻译成问题。
 * 本文件配套的 text2sql_golden_set.json 即是这种思路的起步集（都基于 public.device 等真实表字段）。
 *
 * 运行：mvn test -Dtest=TextToSqlExecutionAccuracyTest
 * 结果打印到控制台，并写到 target/ex_accuracy_result.txt。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TextToSqlExecutionAccuracyTest {

    /** 是否启用自纠错循环。false=快（单次生成）；true=贴近生产但耗时翻倍。 */
    private static final boolean USE_SELF_CORRECTION = false;

    /** 是否开思维链（CoT）。默认关，避免和 gold 的简洁写法产生风格偏差。 */
    private static final boolean USE_COT = false;

    private static final String SCHEMA = "public";

    @Autowired
    private TextToSqlOrchestrator orchestrator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void executionAccuracy() throws Exception {
        // 1) 读黄金集
        List<GoldenCase> cases = loadGoldenSet();
        System.out.println("黄金集规模 = " + cases.size() + " 题");

        int total = 0, correct = 0, goldError = 0, unsafe = 0;
        List<String> failLines = new ArrayList<>();
        // 按类别统计（看哪类问题最容易错）
        Map<String, int[]> byCategory = new LinkedHashMap<>();

        for (GoldenCase c : cases) {
            total++;
            byCategory.computeIfAbsent(c.category(), k -> new int[]{0, 0}); // [总数, 正确数]

            // 2) 走完整生产链路生成 SQL（和线上 /ai/sql 接口同一条流水线）
            TextToSqlRequest req = new TextToSqlRequest();
            req.setQuestion(c.question());
            req.setSchema(SCHEMA);
            req.setExecute(true);                 // 让编排器真的执行，拿到 trace.getData()
            req.setUseSelfCorrection(USE_SELF_CORRECTION);
            req.setUseCot(USE_COT);
            req.setUseExemplar(true);
            req.setUseGlossary(true);
            req.setUseKnowledge(true);
            req.setUseWhereHint(true);
            req.setUseBusinessMetric(true);

            TextToSqlTrace trace = orchestrator.run(req);
            String genSql = trace.getFinalSql();
            boolean safe = trace.getSafety() != null && trace.getSafety().safe();

            // 3) 执行 gold SQL（自己跑，限制行数/超时与线上一致）
            Set<String> goldRows;
            try {
                goldRows = queryRows(c.goldSql());
            } catch (Exception e) {
                goldError++;
                failLines.add(String.format(Locale.ROOT, "[%s] GOLD_SQL 执行失败(黄金集数据有误): %s | %s",
                        c.id(), c.goldSql(), e.getMessage()));
                continue; // 黄金集自己错了，不计入分母
            }

            // 4) 安全拦截 = 直接判错
            if (!safe) {
                unsafe++;
                failLines.add(String.format(Locale.ROOT, "[%s] 被安全护栏拦截，未执行。生成SQL=%s",
                        c.id(), nullSafe(genSql)));
                continue;
            }

            // 5) 比对结果集
            Set<String> genRows = queryRows(genSql); // 用同一份执行逻辑，保证可比
            boolean ok = genRows.equals(goldRows);
            if (ok) correct++;
            byCategory.get(c.category())[0]++;
            if (ok) byCategory.get(c.category())[1]++;

            if (!ok) {
                failLines.add(String.format(Locale.ROOT,
                        "[%s] EX失败 | 问题=%s%n    生成SQL=%s%n    goldSQL=%s%n    生成行数=%d gold行数=%d",
                        c.id(), c.question(), nullSafe(genSql), c.goldSql(),
                        genRows.size(), goldRows.size()));
            }
            System.out.printf(Locale.ROOT, "[%s] %s | EX=%s | 生成行=%d gold行=%d%n",
                    c.id(), ok ? "✅" : "❌", ok ? "PASS" : "FAIL", genRows.size(), goldRows.size());
        }

        // 6) 汇总
        int denom = total - goldError;
        double ex = denom == 0 ? 0 : correct * 100.0 / denom;
        String table = renderTable(total, correct, denom, goldError, unsafe, ex, byCategory, failLines);
        System.out.println("\n" + table);
        writeToFile(table);
    }

    // ===================== 黄金集加载 =====================
    private List<GoldenCase> loadGoldenSet() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/text2sql_golden_set.json")) {
            if (in == null) throw new IllegalStateException("找不到 src/test/resources/text2sql_golden_set.json");
            JsonNode arr = new ObjectMapper().readTree(in);
            List<GoldenCase> list = new ArrayList<>();
            for (JsonNode n : arr) {
                list.add(new GoldenCase(
                        n.get("id").asText(),
                        n.get("question").asText(),
                        n.get("goldSql").asText(),
                        n.get("category").asText(""),
                        n.get("difficulty").asText("")));
            }
            return list;
        }
    }

    // ===================== 结果集执行 + 归一化 =====================
    /** 用和线上一致的边界（限行 1000 / 超时 10s）执行 SQL，返回「拍平后的行多集合」。 */
    private Set<String> queryRows(String sql) {
        int maxRows = 1000, timeout = 10;
        List<Map<String, Object>> rows = jdbcTemplate.query(connection -> {
            var st = connection.prepareStatement(sql.strip().replaceAll(";\\s*$", ""));
            st.setMaxRows(maxRows);
            st.setQueryTimeout(timeout);
            return st;
        }, (rs, rowNum) -> {
            var md = rs.getMetaData();
            var row = new LinkedHashMap<String, Object>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                row.put(md.getColumnLabel(i), rs.getObject(i));
            }
            return row;
        });
        // 归一化：每行内部按值排序（忽略列名/列顺序），所有行再排序后组成多集合
        Set<String> set = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            List<String> cells = new ArrayList<>();
            for (Object v : row.values()) {
                cells.add(v == null ? "⟨NULL⟩" : v.toString());
            }
            cells.sort(Comparator.naturalOrder());
            set.add(String.join("‖", cells));
        }
        return set;
    }

    // ===================== 结果渲染 =====================
    private String renderTable(int total, int correct, int denom, int goldError, int unsafe,
                               double ex, Map<String, int[]> byCategory, List<String> failLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("========================================\n");
        sb.append("L4 执行准确率（EX）结果\n");
        sb.append("========================================\n");
        sb.append(String.format(Locale.ROOT, "总题数=%d  黄金集错误(剔除)=%d  安全拦截=%d%n", total, goldError, unsafe));
        sb.append(String.format(Locale.ROOT, "有效题数=%d  正确=%d%n", denom, correct));
        sb.append(String.format(Locale.ROOT, ">>> 执行准确率 EX = %.1f%%%n%n", ex));
        sb.append("按类别切片：\n");
        for (Map.Entry<String, int[]> e : byCategory.entrySet()) {
            int[] v = e.getValue();
            double r = v[0] == 0 ? 0 : v[1] * 100.0 / v[0];
            sb.append(String.format(Locale.ROOT, "  %-12s 正确 %d/%d  (%.1f%%)%n", e.getKey(), v[1], v[0], r));
        }
        if (!failLines.isEmpty()) {
            sb.append("\n失败明细：\n");
            for (String s : failLines) sb.append(s).append("\n");
        }
        return sb.toString();
    }

    private void writeToFile(String table) {
        try {
            Files.createDirectories(Paths.get("target"));
            Files.writeString(Paths.get("target/ex_accuracy_result.txt"), table, StandardCharsets.UTF_8);
            System.out.println("结果已写入 target/ex_accuracy_result.txt");
        } catch (Exception e) {
            System.out.println("写入结果文件失败（不影响评测）：" + e.getMessage());
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /** 黄金集单条：id / 问题 / 标准答案SQL / 类别 / 难度 */
    private record GoldenCase(String id, String question, String goldSql, String category, String difficulty) {}
}
