package com.springai.springai.eval;

import com.springai.springai.demo.entity.SqlResult;
import com.springai.springai.text2sql.TextToSqlOrchestrator;
import com.springai.springai.text2sql.TextToSqlRequest;
import com.springai.springai.text2sql.TextToSqlTrace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * L3 评测：规则消融（Rule Ablation）。
 *
 * 【目的】
 * 把"我觉得这条规则有用"变成"数据证明这条规则有用"。
 * 做法：固定 5 道真实业务题 + 全开知识底座（few-shot/术语/JOIN/接口映射都开），
 * 然后逐条单独【关闭】某条规则（1~10），对比关闭前后的可执行率/非空率/耗时。
 *
 * 【怎么看结果】
 *   - 某规则关闭后指标明显下跌 → 这条规则真有用，不能删
 *   - 关闭后指标几乎不变 → 这条规则可能是废话，可删/合并
 *   - 关闭后指标反而上升 → 这条规则在干扰，考虑改写
 *
 * 运行：
 *   mvn test -Dtest=TextToSqlRuleAblationTest
 * 结果同时打印到控制台，并写到 target/l3_ablation_result.txt 方便复盘。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TextToSqlRuleAblationTest {

    private static final String SCHEMA = "public";

    @Autowired
    private TextToSqlOrchestrator orchestrator;

    /** 5 道真实业务题（与 MinimalEval 一致，覆盖计数/排序/JOIN/聚合/时间） */
    private static final List<Case> CASES = List.of(
            new Case("Q1", "设备总共有多少台？"),
            new Case("Q2", "列出最近创建的 5 台设备名称和创建时间。"),
            new Case("Q3", "查询最近一次停机的设备名称、停机原因名称和停机时长。"),
            new Case("Q4", "按项目统计各项目有多少台设备？"),
            new Case("Q5", "查询最近一个月每个项目的停机总时长，并显示项目名称。")
    );

    /** 消融配置：null = baseline（全开）；其余 = 单独关闭该编号规则 */
    private static final List<Integer> RULES = Arrays.asList(null, 1, 2, 3, 4, 5, 10, 6, 7, 8, 9);

    @Test
    void ablation() {
        Map<String, Metrics> results = new LinkedHashMap<>();
        for (Integer rule : RULES) {
            String label = rule == null ? "baseline 全开" : "关闭规则 " + rule;
            Metrics m = runConfig(rule);
            results.put(label, m);
            System.out.printf(Locale.ROOT, "[%s] 可执行率=%.0f%% 非空率=%.0f%% 平均耗时=%.0fms%n",
                    label, m.execRate(), m.nonEmptyRate(), m.avgLatency());
        }
        String table = renderTable(results);
        System.out.println("\n" + table);
        writeToFile(table);
    }

    private Metrics runConfig(Integer disabledRule) {
        int total = 0, exec = 0, nonEmpty = 0;
        long sumLatency = 0;
        for (Case c : CASES) {
            TextToSqlRequest req = new TextToSqlRequest();
            req.setQuestion(c.question());
            req.setSchema(SCHEMA);
            req.setExecute(true);
            req.setUseCot(false);
            req.setUseSelfCorrection(false);
            req.setUseExemplar(true);
            req.setUseGlossary(true);
            req.setUseKnowledge(true);
            req.setUseForeignKey(true);
            req.setUseInterfaceMap(true);
            if (disabledRule != null) {
                req.setDisabledRules(Set.of(disabledRule));
            }
            long start = System.nanoTime();
            try {
                TextToSqlTrace trace = orchestrator.run(req);
                long latency = (System.nanoTime() - start) / 1_000_000;
                total++;
                sumLatency += latency;
                SqlResult sr = trace.getSqlResult();
                String sql = sr == null ? "" : nullSafe(sr.getSql());
                boolean safe = trace.getSafety() != null && trace.getSafety().safe();
                boolean executable = safe && !sql.isBlank();
                if (executable) exec++;
                int rows = trace.getData() == null ? 0 : trace.getData().size();
                if (rows > 0) nonEmpty++;
            } catch (Exception e) {
                long latency = (System.nanoTime() - start) / 1_000_000;
                total++;
                sumLatency += latency;
            }
        }
        return new Metrics(total, exec, nonEmpty, sumLatency);
    }

    private String renderTable(Map<String, Metrics> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("========================================\n");
        sb.append("L3 规则消融结果（每配置 5 题，全开知识底座）\n");
        sb.append("========================================\n");
        sb.append(String.format(Locale.ROOT, "%-16s %10s %10s %12s %12s%n",
                "配置", "可执行率", "非空率", "平均耗时", "vs基线Δ可执行"));
        Metrics base = results.get("baseline 全开");
        double baseExec = base == null ? 0 : base.execRate();
        for (Map.Entry<String, Metrics> e : results.entrySet()) {
            Metrics m = e.getValue();
            double delta = m.execRate() - baseExec;
            sb.append(String.format(Locale.ROOT, "%-16s %9.0f%% %9.0f%% %10.0fms %11s%n",
                    e.getKey(), m.execRate(), m.nonEmptyRate(), m.avgLatency(),
                    (base == null || e.getKey().equals("baseline 全开")) ? "-" : String.format(Locale.ROOT, "%+.0f%%", delta)));
        }
        return sb.toString();
    }

    private void writeToFile(String table) {
        try {
            Files.createDirectories(Paths.get("target"));
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(
                    Paths.get("target/l3_ablation_result.txt"), StandardCharsets.UTF_8))) {
                w.print(table);
            }
            System.out.println("结果已写入 target/l3_ablation_result.txt");
        } catch (Exception e) {
            System.out.println("写入结果文件失败（不影响评测）：" + e.getMessage());
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private record Case(String id, String question) {}

    private record Metrics(int total, int exec, int nonEmpty, long sumLatency) {
        double execRate() { return total == 0 ? 0 : exec * 100.0 / total; }
        double nonEmptyRate() { return total == 0 ? 0 : nonEmpty * 100.0 / total; }
        double avgLatency() { return total == 0 ? 0 : (double) sumLatency / total; }
    }
}
