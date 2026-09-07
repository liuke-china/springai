package com.springai.springai.eval;

import com.springai.springai.smalldemo.entity.SqlResult;
import com.springai.springai.text2sql.TextToSqlOrchestrator;
import com.springai.springai.text2sql.TextToSqlRequest;
import com.springai.springai.text2sql.TextToSqlTrace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Text-to-SQL 最小评测：5 道真实业务题 × 全开 / 全关。
 *
 * 【你只要改一个参数】
 *   ALL_FULL_ON = true   → 全开（few-shot + 术语 + JOIN + 接口映射 全部启用）
 *   ALL_FULL_ON = false  → 全关（只靠表结构检索）
 *
 * 运行：
 *   mvn test -Dtest=TextToSqlMinimalEvalTest
 *
 * 输出：
 *   - 每道题的：完整请求 / AI 生成的 SQL / 实际数据 / 耗时
 *   - 末尾汇总：可执行率、非空结果率、平均耗时、平均返回行数
 *
 * 注意：
 *   - 业务正确性由你按 expected 字段肉眼判断（脚本不自动判定 SQL 业务是否正确）
 *   - 自动纠错 (useSelfCorrection) 与 思维链 (useCot) 固定关闭，避免干扰知识效果
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TextToSqlMinimalEvalTest {

    /** === 你唯一需要切换的参数 === */
    private static final boolean ALL_FULL_ON = false;

    private static final String SCHEMA = "public";

    @Autowired
    private TextToSqlOrchestrator orchestrator;

    /**
     * 5 道真实业务题，覆盖：单表计数、排序、多表 JOIN、聚合、JOIN+时间过滤
     * expected 字段仅供肉眼判断，程序不做硬判定
     */
    private static final List<Case> CASES = List.of(
            new Case("Q1", "设备总共有多少台？",
                    "返回一行一列的数字 >= 0；数字越大说明召回越全"),
            new Case("Q2", "列出最近创建的 5 台设备名称和创建时间。",
                    "约 5 行，含设备名 + 时间，按时间倒序"),
            new Case("Q3", "查询最近一次停机的设备名称、停机原因名称和停机时长。",
                    "JOIN downtime_record + reason + device，返回最近一条停机记录"),
            new Case("Q4", "按项目统计各项目有多少台设备？",
                    "按 project_name 分组返回若干行 (project_name, count)"),
            new Case("Q5", "查询最近一个月每个项目的停机总时长，并显示项目名称。",
                    "JOIN + 时间过滤 + GROUP BY 项目名")
    );

    @Test
    void runAllCases() {
        String modeLabel = ALL_FULL_ON ? "全开 B_ON" : "全关 A_OFF";
        System.out.println("\n========================================");
        System.out.println("Text-to-SQL 最小评测开始，当前模式：" + modeLabel);
        System.out.println("切换模式：改 ALL_FULL_ON = true / false 后重新运行");
        System.out.println("========================================");

        List<RunResult> results = new ArrayList<>();

        for (Case c : CASES) {
            RunResult r = runOnce(c);
            results.add(r);
            printOne(r);
        }

        printSummary(results);
    }

    private RunResult runOnce(Case c) {
        TextToSqlRequest req = new TextToSqlRequest();
        req.setQuestion(c.question());
        req.setSchema(SCHEMA);
        req.setExecute(true);
        req.setUseCot(false);
        req.setUseSelfCorrection(false);
        // 关键开关：跟着 ALL_FULL_ON
        req.setUseExemplar(ALL_FULL_ON);
        req.setUseGlossary(ALL_FULL_ON);
        req.setUseKnowledge(ALL_FULL_ON);
        req.setUseForeignKey(ALL_FULL_ON);
        req.setUseInterfaceMap(ALL_FULL_ON);

        long start = System.nanoTime();
        try {
            TextToSqlTrace trace = orchestrator.run(req);
            long latencyMs = (System.nanoTime() - start) / 1_000_000;

            SqlResult sr = trace.getSqlResult();
            String sql = sr == null ? "" : nullSafe(sr.getSql());
            boolean safe = trace.getSafety() != null && trace.getSafety().safe();
            boolean executable = safe && !sql.isBlank();
            int rowCount = trace.getData() == null ? 0 : trace.getData().size();
            boolean nonEmpty = rowCount > 0;

            return new RunResult(c, modeLabel(), sql, safe, executable, nonEmpty,
                    rowCount, latencyMs, nullSafe(trace.getExecuteError()));
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            return new RunResult(c, modeLabel(), "", false, false, false,
                    0, latencyMs, e.getClass().getSimpleName() + ": " + nullSafe(e.getMessage()));
        }
    }

    private String modeLabel() {
        return ALL_FULL_ON ? "全开 B_ON" : "全关 A_OFF";
    }

    private void printOne(RunResult r) {
        System.out.println("\n----------------------------------------");
        System.out.printf(Locale.ROOT, "[%s] %s%n", r.caseId(), r.variant());
        System.out.println("问题    : " + r.caseObj().question());
        System.out.println("期望参考: " + r.caseObj().expected());
        System.out.println("耗时    : " + r.latencyMs() + " ms");
        System.out.println("SQL 通过安全校验: " + (r.safe() ? "是" : "否"));
        System.out.println("可执行  : " + (r.executable() ? "是" : "否"));
        System.out.println("返回行数: " + r.rowCount());
        if (!r.executeError().isBlank()) {
            System.out.println("错误    : " + r.executeError());
        }
        System.out.println("--- AI 生成的 SQL ---");
        System.out.println(r.sql().isBlank() ? "(空)" : r.sql());

        // 实际返回数据由调用方决定是否打印；这里复用 trace.data 已经在 RunResult 里统计
    }

    private void printSummary(List<RunResult> results) {
        long total = results.size();
        long exec = results.stream().filter(RunResult::executable).count();
        long nonEmpty = results.stream().filter(RunResult::nonEmpty).count();
        double avgLatency = results.stream().mapToLong(RunResult::latencyMs).average().orElse(0);
        double avgRows = results.stream().mapToInt(RunResult::rowCount).average().orElse(0);

        System.out.println("\n========================================");
        System.out.println("汇总（业务正确性请按每题 expected 自行判断）");
        System.out.println("========================================");
        System.out.printf(Locale.ROOT, "题目总数      : %d%n", total);
        System.out.printf(Locale.ROOT, "可执行率      : %d/%d = %.1f%%%n",
                exec, total, total == 0 ? 0 : exec * 100.0 / total);
        System.out.printf(Locale.ROOT, "非空结果率    : %d/%d = %.1f%%%n",
                nonEmpty, total, total == 0 ? 0 : nonEmpty * 100.0 / total);
        System.out.printf(Locale.ROOT, "平均耗时      : %.0f ms%n", avgLatency);
        System.out.printf(Locale.ROOT, "平均返回行数  : %.1f 行%n", avgRows);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /** 题目定义 */
    private record Case(String id, String question, String expected) {}

    /** 一次跑题的结果汇总 */
    private record RunResult(
            Case caseObj,
            String variant,
            String sql,
            boolean safe,
            boolean executable,
            boolean nonEmpty,
            int rowCount,
            long latencyMs,
            String executeError
    ) {
        String caseId() { return caseObj.id(); }
    }
}