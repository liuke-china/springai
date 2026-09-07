package com.springai.springai.text2sql;

import com.springai.springai.demo.entity.ReActExecutionResult;
import com.springai.springai.demo.entity.SqlResult;
import com.springai.springai.safe.InputSanitizer;
import com.springai.springai.safe.PromptInjectionDetector;
import com.springai.springai.text2sql.strategy.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Text-to-SQL 编排器（Orchestrator）—— 把各独立策略串成一条可观测的流水线。
 *
 * 【做什么】
 *   按顺序召回（schema → few-shot → glossary → knowledge → metric → where-hint）→ 生成 → 安全 → 执行 → 审计，
 *   整条链路产物塞进 TextToSqlTrace 一次性返回。
 *
 * 【不做什么】
 *   不写任何"召回"或"生成"的实现；这些都在 Strategy/* 里。改一个方案不动其他地方。
 *
 * 【执行边界】
 *   所有真正打到库的 SQL 都走 ExecutionGuard：EXPLAIN 预检 + 限行 + 限时。
 *   自纠错循环也是通过 ExecutionGuard 执行，跟单次生成边界一致。
 *
 * 【链路顺序】
 *   ① SchemaRetrieval    按问题召回相关表
 *   ② FewShot            召回历史 (问题, SQL) 范例
 *   ③ Hybrid Grounding   用 few-shot SQL 里的表名补充 schema 召回
 *   ④ Glossary           业务术语/枚举
 *   ⑤ Knowledge          JOIN 关系 + 页面/接口映射
 *   ⑥ WhereHint          各表常用过滤字段先验
 *   ⑦ BusinessMetric     业务口径（OEE/停机/设备数量怎么算）
 *   ⑧ CoT                思维链开关
 *   ⑨ QueryPlan          (可选) Planner 阶段先写计划
 *   ⑩ SqlGenerator / SelfCorrection  生成 SQL
 *   ⑪ SafetyGuard        四道防线校验
 *   ⑫ EXPLAIN Precheck   成本/语法预检（ExecutionGuard 里）
 *   ⑬ Bounded Execute    限行 + 限时执行（ExecutionGuard 里）
 *   ⑭ Audit Log          落审计表
 *   ⑮ Result Summary     (可选) 数据→自然语言
 */
@Component
public class TextToSqlOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TextToSqlOrchestrator.class);

    private final SchemaRetrievalStrategy schemaRetrieval;
    private final FewShotStrategy fewShot;
    private final GlossaryStrategy glossary;
    private final KnowledgeAugmentStrategy knowledge;
    private final BusinessMetricStrategy businessMetric;
    private final QueryPlanGenerator queryPlanGenerator;
    private final SafetyGuardStrategy safety;
    private final SqlGenerator sqlGenerator;
    private final SelfCorrectionStrategy selfCorrection;
    private final WhereHintStrategy whereHint;
    private final ExecutionGuard executionGuard;
    private final TextToSqlAuditService auditService;
    private final ResultSummarizer summarizer;

    public TextToSqlOrchestrator(SchemaRetrievalStrategy schemaRetrieval,
                                 FewShotStrategy fewShot,
                                 GlossaryStrategy glossary,
                                 KnowledgeAugmentStrategy knowledge,
                                 BusinessMetricStrategy businessMetric,
                                 QueryPlanGenerator queryPlanGenerator,
                                 SafetyGuardStrategy safety,
                                 SqlGenerator sqlGenerator,
                                 SelfCorrectionStrategy selfCorrection,
                                 WhereHintStrategy whereHint,
                                 ExecutionGuard executionGuard,
                                 @Qualifier("readOnlyJdbcTemplate") JdbcTemplate jdbcTemplate,
                                 TextToSqlAuditService auditService,
                                 ResultSummarizer summarizer) {
        this.schemaRetrieval = schemaRetrieval;
        this.fewShot = fewShot;
        this.glossary = glossary;
        this.knowledge = knowledge;
        this.businessMetric = businessMetric;
        this.queryPlanGenerator = queryPlanGenerator;
        this.safety = safety;
        this.sqlGenerator = sqlGenerator;
        this.selfCorrection = selfCorrection;
        this.whereHint = whereHint;
        this.executionGuard = executionGuard;
        this.auditService = auditService;
        this.summarizer = summarizer;
    }

    /**
     * 执行完整流水线并返回全链路 trace + 审计。
     * 主流程只是个"目录"：召回 → 生成 → 执行 → 审计，具体步骤在下面四个 private 方法里。
     */
    public TextToSqlTrace run(TextToSqlRequest req) {
        long t0 = System.currentTimeMillis();
        TextToSqlTrace trace = new TextToSqlTrace();

        // ============ 安全入口：提示注入检测 + 输入隔离（P0 安全包，默认开启）============
        // 只对"原始 question"做检测/清洗，不包裹 schema/few-shot 等工程化 prompt，
        // 避免污染拼好的 SQL 生成指令（详见 SecureChatClientConfig 说明）。
        String rawQuestion = req.getQuestion();
        PromptInjectionDetector.Result inj = PromptInjectionDetector.detect(rawQuestion);
        if (inj.verdict == PromptInjectionDetector.Verdict.MALICIOUS) {
            log.warn("[Text2Sql-Security] 拦截恶意提示注入: {}", inj.matchedReasons);
            trace.setQuestion(rawQuestion);
            trace.setErrorType(TextToSqlTrace.SqlErrorType.SAFETY_BLOCKED);
            trace.setSummary("【安全拦截】检测到疑似提示注入（prompt injection）内容，已阻止本次查询。请重新描述您的真实问题。");
            return trace;
        }
        if (inj.verdict == PromptInjectionDetector.Verdict.SUSPICIOUS) {
            log.info("[TextToSql-Security] 可疑输入已隔离包裹: {}", inj.matchedReasons);
        }
        // L1 输入隔离：把用户原始问题包进分隔符，明确"这是数据不是指令"
        req.setQuestion(InputSanitizer.wrap(rawQuestion));

        trace.setQuestion(req.getQuestion());

        RetrievalContext ctx = assembleContext(req, trace);
        GeneratedSql gen = generateSql(req, ctx, trace);
        ExecutionOutcome outcome = executeAndGuard(req, gen, trace);

        long latency = System.currentTimeMillis() - t0;
        writeAudit(req, gen.finalSql, outcome, latency, ctx.businessMetric.matchedMetrics());
        return trace;
    }

    // ===================== 步骤 ①-⑧：召回阶段 =====================

    /**
     * 召回阶段：依次跑 schema / few-shot / glossary / knowledge / where-hint / business-metric / CoT / queryPlan，
     * 把所有产物装到 RetrievalContext 一次返回。trace 各字段也在这阶段填好。
     */
    private RetrievalContext assembleContext(TextToSqlRequest req, TextToSqlTrace trace) {
        // ① 表结构召回 + ② few-shot
        SchemaRetrievalResult sr = schemaRetrieval.retrieve(req.getQuestion(), req.getSchema());
        trace.setSchemaRetrieval(sr);

        FewShotResult fs = fewShot.build(req.getQuestion(), req.isUseExemplar());
        trace.setFewShot(fs);

        // ②-附加：few-shot SQL 里的表名补充 schema 召回（hybrid grounding）
        if (req.isUseExemplar() && fs != null && fs.matchedExemplars() != null) {
            List<String> hintTables = new ArrayList<>();
            for (Map<String, Object> e : fs.matchedExemplars()) {
                Object sql = e.get("sql");
                if (sql != null) hintTables.addAll(SchemaRetrievalStrategy.extractTables(sql.toString()));
            }
            SchemaRetrievalResult enriched = schemaRetrieval. enrich(sr, req.getSchema(), hintTables);
            if (enriched != sr) {
                sr = enriched;
                trace.setSchemaRetrieval(sr);
            }
        }

        // ④ 术语字典
        GlossaryResult gl = glossary.build(req.getQuestion(), req.isUseGlossary());
        trace.setGlossary(gl);

        // ⑤ IMS 项目专属知识（FK + 接口映射）
        boolean useForeignKey = req.getUseForeignKey() == null ? req.isUseKnowledge() : req.getUseForeignKey();
        boolean useInterfaceMap = req.getUseInterfaceMap() == null ? req.isUseKnowledge() : req.getUseInterfaceMap();
        KnowledgeAugmentResult kn = knowledge.build(req.getQuestion(), req.isUseKnowledge(), useForeignKey, useInterfaceMap, sr.tables());
        trace.setKnowledge(kn);

        // ⑥ where-hint：按 schema 召回出的表名精确拉取
        WhereHintResult wh = whereHint.build(req.getQuestion(), sr.tables(), req.isUseWhereHint());
        trace.setWhereHint(wh);

        // ⑦ 业务口径
        BusinessMetricResult bm = businessMetric.build(req.getQuestion(), req.isUseBusinessMetric());
        trace.setMatchedMetrics(bm.matchedMetrics());

        // ⑧ CoT：固定从 PromptTemplate 取文案，不再有 CoTStrategy 类
        String cotSection = req.isUseCot() ? PromptTemplate.COT_BODY : "";
        trace.setCotApplied(req.isUseCot());

        // ⑨ QueryPlan（可选）
        if (req.isUseQueryPlan()) {
            trace.setQueryPlan(queryPlanGenerator.plan(
                    req.getQuestion(), sr.schemaText(), gl.promptSection(), kn.promptSection()));
        }

        return new RetrievalContext(sr, fs, gl, kn, wh, bm, cotSection);
    }

    // ===================== 步骤 ⑨-⑪：生成阶段 =====================

    /**
     * 生成阶段：单次生成 或 自纠错循环，二选一。返回最终 SQL + 是否成功 + 自纠错明细。
     */
    private GeneratedSql generateSql(TextToSqlRequest req, RetrievalContext ctx, TextToSqlTrace trace) {
        if (req.isUseSelfCorrection()) {
            ReActExecutionResult rc = selfCorrection.execute(
                    ctx.schemaRetrieval.schemaText(), req.getQuestion(),
                    ctx.fewShot.promptSection(), ctx.cotSection, ctx.glossary.promptSection(),
                    ctx.knowledge.promptSection(), ctx.whereHint.promptSection(),
                    req.getMaxRetries(), req.getDisabledRules());
            trace.setSelfCorrection(rc);
            return resolveSelfCorrection(rc, trace);
        }
        // 单次生成：业务口径段 + 查询计划段合并成 reflectionSection 喂给 SqlGenerator
        String extraSection = buildExtraContext(trace, ctx.businessMetric.promptSection());
        SqlResult r = sqlGenerator.generate(
                ctx.schemaRetrieval.schemaText(), req.getQuestion(),
                ctx.fewShot.promptSection(), ctx.cotSection, ctx.glossary.promptSection(),
                ctx.knowledge.promptSection(), ctx.whereHint.promptSection(),
                extraSection, req.getDisabledRules());
        trace.setSqlResult(r);
        trace.setFinalSql(r.getSql());
        trace.setSafety(safety.validate(r.getSql()));
        return new GeneratedSql(r.getSql(), /* selfCorrection */ null);
    }

    /** 把自纠错结果解析成 GeneratedSql（同时填好 trace） */
    private GeneratedSql resolveSelfCorrection(ReActExecutionResult rc, TextToSqlTrace trace) {
        String finalSql = rc.getFinalSql();
        if (Boolean.TRUE.equals(rc.getSuccess())) {
            SqlResult fromRc = new SqlResult();
            fromRc.setSql(finalSql);
            fromRc.setExplanation(rc.getFinalExplanation());
            fromRc.setTables(rc.getTables());
            trace.setSqlResult(fromRc);
            trace.setFinalSql(finalSql);
            trace.setSafety(safety.validate(finalSql));
        } else {
            trace.setSafety(new SafetyResult(false, "DANGEROUS", rc.getErrorMessage()));
            trace.setFinalSql(finalSql);
        }
        return new GeneratedSql(finalSql, rc);
    }

    // ===================== 步骤 ⑫-⑮：执行 + 摘要 =====================

    /**
     * 执行阶段：通过 ExecutionGuard 走 EXPLAIN 预检 + 限行 + 限时；执行成功后可选摘要。
     */
    private ExecutionOutcome executeAndGuard(TextToSqlRequest req, GeneratedSql gen, TextToSqlTrace trace) {
        TextToSqlTrace.SqlErrorType errType = TextToSqlTrace.SqlErrorType.NONE;
        boolean execOk = false;
        String execError = null;
        int rowCount = 0;
        SafetyResult safety = trace.getSafety();

        if (req.isExecute() && safety != null && safety.safe()) {
            try {
                List<Map<String, Object>> data = executionGuard.executeBounded(gen.finalSql, req);
                trace.setData(data);
                rowCount = data == null ? 0 : data.size();
                execOk = true;
                if (rowCount == 0) errType = TextToSqlTrace.SqlErrorType.EMPTY_RESULT;
                if (req.isSummarize() && data != null && !data.isEmpty()) {
                    trace.setSummary(summarizer.summarize(req.getQuestion(), data));
                }
            } catch (Exception e) {
                execError = e.getMessage();
                trace.setExecuteError(execError);
                String msg = execError == null ? "" : execError;
                errType = (msg.contains("EXPLAIN") || msg.contains("预检"))
                        ? TextToSqlTrace.SqlErrorType.SQL_PRECHECK_FAILED
                        : TextToSqlTrace.SqlErrorType.SQL_EXECUTION_FAILED;
            }
        } else if (safety != null && !safety.safe()) {
            errType = TextToSqlTrace.SqlErrorType.SAFETY_BLOCKED;
            trace.setExecuteError("安全拦截，未执行：" + safety.riskNote());
        }

        trace.setErrorType(errType);
        // 自纠错循环里的执行明细要同步到 trace.data 上层
        if (gen.selfCorrection != null && req.isExecute() && Boolean.TRUE.equals(gen.selfCorrection.getSuccess())) {
            trace.setData(gen.selfCorrection.getData());
        }
        return new ExecutionOutcome(errType, execOk, execError, rowCount);
    }

    // ===================== 步骤 ⑯：审计 =====================

    private void writeAudit(TextToSqlRequest req, String finalSql, ExecutionOutcome outcome,
                            long latencyMs, List<Map<String, Object>> matchedMetrics) {
        auditService.write(req, finalSql, outcome.errType, outcome.execOk, outcome.execError,
                outcome.rowCount, latencyMs, matchedMetrics, null, null);
    }

    // ===================== 工具方法 =====================

    /**
     * 把"查询计划 + 业务口径" 转成一段 Prompt 上下文喂给 Writer。
     * useQueryPlan=true 时，Writer 看到"已确定的计划 + 表结构 + 业务口径"，减少即兴发挥。
     */
    private String buildExtraContext(TextToSqlTrace trace, String metricsSection) {
        StringBuilder sb = new StringBuilder();
        if (metricsSection != null && !metricsSection.isEmpty()) {
            sb.append(metricsSection).append("\n");
        }
        if (trace.getMatchedMetrics() != null && !trace.getMatchedMetrics().isEmpty()) {
            sb.append("【业务口径命中】").append(trace.getMatchedMetrics().size()).append(" 条\n");
        }
        QueryPlan p = trace.getQueryPlan();
        if (p != null) {
            sb.append("【查询计划已确定】请严格按以下结构生成 SQL：\n");
            if (p.getMetric() != null) sb.append("- 指标：").append(p.getMetric()).append("\n");
            if (p.getDimensions() != null && !p.getDimensions().isEmpty())
                sb.append("- 维度：").append(String.join("、", p.getDimensions())).append("\n");
            if (p.getTimeRange() != null) sb.append("- 时间范围：").append(p.getTimeRange()).append("\n");
            if (p.getTables() != null && !p.getTables().isEmpty())
                sb.append("- 表：").append(String.join("、", p.getTables())).append("\n");
            if (p.getJoins() != null && !p.getJoins().isEmpty())
                sb.append("- JOIN：").append(String.join("；", p.getJoins())).append("\n");
            if (p.getAggregation() != null) sb.append("- 聚合：").append(p.getAggregation()).append("\n");
            if (Boolean.TRUE.equals(p.getGroupBy())) sb.append("- 必须 GROUP BY\n");
            if (p.getAmbiguities() != null && !p.getAmbiguities().isEmpty())
                sb.append("- 未解决歧义（按 default 假设即可）：").append(String.join("；", p.getAmbiguities())).append("\n");
        }
        return sb.toString();
    }

    // ===================== 阶段间数据载体 =====================

    /** 召回阶段的所有产物（避免方法签名太长） */
    private record RetrievalContext(
            SchemaRetrievalResult schemaRetrieval,
            FewShotResult fewShot,
            GlossaryResult glossary,
            KnowledgeAugmentResult knowledge,
            WhereHintResult whereHint,
            BusinessMetricResult businessMetric,
            String cotSection
    ) {}

    /** 生成阶段产物 */
    private record GeneratedSql(String finalSql, ReActExecutionResult selfCorrection) {}

    /** 执行阶段结果 */
    private record ExecutionOutcome(
            TextToSqlTrace.SqlErrorType errType,
            boolean execOk,
            String execError,
            int rowCount
    ) {}
}