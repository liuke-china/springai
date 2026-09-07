package com.springai.springai.text2sql.strategy;

import com.springai.springai.demo.entity.ReActExecutionResult;
import com.springai.springai.demo.entity.RetryRecord;
import com.springai.springai.demo.entity.SqlResult;
import com.springai.springai.text2sql.ExecutionGuard;
import com.springai.springai.text2sql.TextToSqlRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自纠错循环（Self-Correction / ReAct loop）—— 企业 NL2SQL 标准增强。
 *
 * 【白话解释】
 *   单次生成：AI 写一条 SQL，错了直接报错。
 *   自纠错：AI 写 SQL → 执行 → 报错 → 把错误回灌给 AI → AI 反思重写 → 再执行，最多 N 次。
 *   等于给 AI 一个"试错-反思"的闭环，显著降低首轮 SQL 出错率。
 *
 * 【做什么】
 *   - 调 SqlGenerator 生成（带历史错误反思段）
 *   - 调 SafetyGuardStrategy 兜底拦截危险 SQL
 *   - 调 ExecutionGuard 执行（限行 + 限时 + EXPLAIN 预检，跟普通执行边界一致）
 *
 * 【不做什么】
 *   不维护"反思段格式"，那段文案统一在 PromptTemplate。
 */
@Component
public class SelfCorrectionStrategy {

    private final SqlGenerator sqlGenerator;
    private final SafetyGuardStrategy safety;
    private final ExecutionGuard executionGuard;

    public SelfCorrectionStrategy(SqlGenerator sqlGenerator,
                                  SafetyGuardStrategy safety,
                                  ExecutionGuard executionGuard) {
        this.sqlGenerator = sqlGenerator;
        this.safety = safety;
        this.executionGuard = executionGuard;
    }

    /**
     * 跑自纠错循环：生成 → 安全校验 → 执行；任一失败积累到错误历史进入下一轮反思。
     *
     * @param maxRetries 最大重试次数（含首轮，共执行 maxRetries+1 次）
     */
    public ReActExecutionResult execute(String schemaText, String question,
                                        String fewShotSection, String cotSection,
                                        String glossarySection, String knowledgeSection,
                                        String whereHintSection, int maxRetries,
                                        Set<Integer> disabledRules) {
        ReActExecutionResult result = new ReActExecutionResult();
        List<RetryRecord> history = new ArrayList<>();
        List<String> errorHistory = new ArrayList<>();
        String reflection = "";
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                SqlResult r = sqlGenerator.generate(schemaText, question, fewShotSection, cotSection,
                        glossarySection, knowledgeSection, whereHintSection, reflection, disabledRules);

                // 1. 安全护栏：危险 SQL 直接拒绝，不发到数据库
                SafetyResult sres = safety.validate(r.getSql());
                if (!sres.safe()) {
                    String msg = "安全拦截：" + sres.riskNote() + "。SQL=" + r.getSql();
                    history.add(buildFailureRecord(attempt, msg));
                    errorHistory.add(msg);
                    reflection = PromptTemplate.REFLECTION_SAFETY_PREFIX + "\n第 " + errorHistory.size() + " 次失败: " + msg + "\n";
                    lastException = new RuntimeException(msg);
                    continue;
                }

                // 2. 执行：统一走 ExecutionGuard（限行/限时/EXPLAIN 预检）
                List<Map<String, Object>> data = executionGuard.executeBounded(r.getSql(), dummyRequest(maxRetries));

                // ✅ 成功
                history.add(buildSuccessRecord(attempt, r, data.size()));
                result.setSuccess(true);
                result.setData(data);
                result.setFinalSql(r.getSql());
                result.setFinalExplanation(r.getExplanation());
                result.setRetryCount(attempt);
                result.setHistory(history);
                result.setTables(r.getTables());
                return result;

            } catch (Exception e) {
                String errorMsg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                history.add(buildFailureRecord(attempt, errorMsg));
                errorHistory.add(errorMsg);
                reflection = buildReflection(errorHistory);
                lastException = e;
            }
        }

        // 循环结束仍失败
        result.setSuccess(false);
        result.setRetryCount(maxRetries);
        result.setHistory(history);
        result.setErrorMessage("自纠错循环 " + maxRetries + " 次重试后仍失败。最后错误: " +
                (lastException != null ? lastException.getMessage() : "未知"));
        return result;
    }

    /** 把错误历史拼成"反思段"喂给下一轮 LLM */
    private static String buildReflection(List<String> errorHistory) {
        StringBuilder sb = new StringBuilder(PromptTemplate.REFLECTION_ERROR_PREFIX).append("\n");
        for (int i = 0; i < errorHistory.size(); i++) {
            sb.append("第 ").append(i + 1).append(" 次失败: ").append(errorHistory.get(i)).append("\n");
        }
        sb.append("\n").append(PromptTemplate.REFLECTION_TAIL).append("\n");
        return sb.toString();
    }

    /** 自纠错用的执行上下文：复用 ExecutionGuard 必需的 maxRows / queryTimeoutSeconds，给默认值 */
    private static TextToSqlRequest dummyRequest(int maxRetries) {
        TextToSqlRequest req = new TextToSqlRequest();
        req.setMaxRows(1000);
        req.setQueryTimeoutSeconds(10);
        return req;
    }

    private static RetryRecord buildSuccessRecord(int attempt, SqlResult sqlResult, int rowCount) {
        RetryRecord record = new RetryRecord();
        record.setAttempt(attempt);
        record.setSql(sqlResult.getSql());
        record.setSuccess(true);
        record.setRowCount(rowCount);
        record.setAiReflection("第 " + (attempt + 1) + " 次成功执行，拿到 " + rowCount + " 条数据");
        return record;
    }

    private static RetryRecord buildFailureRecord(int attempt, String errorMessage) {
        RetryRecord record = new RetryRecord();
        record.setAttempt(attempt);
        record.setSuccess(false);
        record.setErrorMessage(errorMessage);
        record.setAiReflection("第 " + (attempt + 1) + " 次失败: " + errorMessage);
        return record;
    }
}