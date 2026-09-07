package com.springai.springai.text2sql;

import com.springai.springai.smalldemo.entity.ReActExecutionResult;
import com.springai.springai.smalldemo.entity.SqlResult;
import com.springai.springai.text2sql.strategy.FewShotResult;
import com.springai.springai.text2sql.strategy.GlossaryResult;
import com.springai.springai.text2sql.strategy.KnowledgeAugmentResult;
import com.springai.springai.text2sql.strategy.SafetyResult;
import com.springai.springai.text2sql.strategy.SchemaRetrievalResult;
import com.springai.springai.text2sql.strategy.WhereHintResult;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Text-to-SQL 全链路追踪（Trace）对象。
 *
 * 每个策略的产物都被原样保留在这里，调用 /ai/sql/trace 即可一次性看到：
 *   - 召回了哪些表（schemaRetrieval）
 *   - 命中了哪几条 few-shot 范例（fewShot）
 *   - 命中了哪些业务术语（glossary）
 *   - 是否启用了 CoT（cotApplied）
 *   - 生成后安全校验结果（safety）
 *   - 自纠错循环历史（selfCorrection，开了才有）
 *   - 最终 SQL 与执行数据（finalSql / data）
 *
 * 这是"每个方案单独成类"后最大的好处：每个方案的贡献都肉眼可见。
 */
@Data
public class TextToSqlTrace {

    /** 统一错误分类：让前端/面试能一眼区分“为什么没拿到数据” */
    public enum SqlErrorType {
        NONE,                  // 成功执行（或不需要执行）
        SAFETY_BLOCKED,       // 安全护栏拦截（非 SELECT/WITH）
        SQL_PRECHECK_FAILED,  // EXPLAIN 预检失败（语法/表/列/权限错）
        SQL_EXECUTION_FAILED, // 执行阶段其他异常
        EMPTY_RESULT          // 执行成功但返回空（可能业务本来就没数据）
    }

    private String question;

    /** 方案①：表结构召回结果 */
    private SchemaRetrievalResult schemaRetrieval;

    /** 方案②：few-shot 范例检索结果 */
    private FewShotResult fewShot;

    /** 方案⑤：业务术语字典检索结果 */
    private GlossaryResult glossary;

    /** 方案⑧：IMS 项目专属知识（已知表关联 + 已知页面/接口）检索结果 */
    private KnowledgeAugmentResult knowledge;

    /** 方案⑨：各表常用过滤字段先验（源码 WHERE 模式）检索结果 */
    private WhereHintResult whereHint;

    /** 方案③：是否启用了思维链 CoT */
    private boolean cotApplied;

    /** 方案⑩：查询计划（Planner 阶段产出，启用 useQueryPlan 时才有） */
    private QueryPlan queryPlan;

    /** 召回的业务口径命中（type=business_metric） */
    private List<Map<String, Object>> matchedMetrics;

    /** 结果自然语言摘要（summarize=true 且有数据时才有） */
    private String summary;

    /** 统一错误分类：NONE/SAFETY_BLOCKED/SQL_PRECHECK_FAILED/SQL_EXECUTION_FAILED/EMPTY_RESULT */
    private SqlErrorType errorType;

    /** 最终生成的 SQL 结果（未走自纠错时为单次生成结果） */
    private SqlResult sqlResult;

    /** 方案④：安全护栏校验结果 */
    private SafetyResult safety;

    /** 方案⑦：自纠错循环结果（未启用为 null） */
    private ReActExecutionResult selfCorrection;

    /** 最终采用的 SQL */
    private String finalSql;

    /** 实际执行拿到的数据（execute=true 且有结果时才有） */
    private List<Map<String, Object>> data;

    /** 执行阶段报错信息（execute=true 且执行异常时） */
    private String executeError;
}
