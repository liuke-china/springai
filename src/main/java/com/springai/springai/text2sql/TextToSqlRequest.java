package com.springai.springai.text2sql;

import lombok.Data;

import java.util.HashSet;
import java.util.Set;

/**
 * Text-to-SQL 流水线的统一入参。
 * 每个"方案开关"单独成字段，方便在 Postman / trace 里逐项开/关做 A/B 对照，看清每个方案的作用。
 */
@Data
public class TextToSqlRequest {

    /** 用户自然语言问题（必填） */
    private String question;

    /** 数据库 schema，默认 public */
    private String schema = "public";

    /** 方案②：是否注入 few-shot 范例（默认开） */
    private boolean useExemplar = true;

    /** 方案③：是否启用思维链 CoT 提示（默认关，便于对照） */
    private boolean useCot = false;

    /** 方案⑤：是否注入业务术语/枚举字典（默认开） */
    private boolean useGlossary = true;

    /** 方案⑧：是否注入 IMS 项目专属知识总开关（兼容旧请求，默认开） */
    private boolean useKnowledge = true;

    /** 方案⑨：是否注入源码 WHERE 过滤字段先验（默认开） */
    private boolean useWhereHint = true;

    /** 是否注入代码挖掘出的真实 JOIN 关系（默认跟随 useKnowledge） */
    private Boolean useForeignKey;

    /** 是否注入页面/接口到表的映射（默认跟随 useKnowledge） */
    private Boolean useInterfaceMap;

    /** 是否注入业务口径（OEE/停机/良品率等的标准计算方式，默认开） */
    private boolean useBusinessMetric = true;

    /** 是否在执行成功后生成结果自然语言摘要（默认关，需要时按需开启） */
    private boolean summarize = false;

    /** L3 评测：关闭指定编号的规则（1-10）。空集合=全部启用，用于量化每条规则的贡献 */
    private Set<Integer> disabledRules = new HashSet<>();

    /** 单次查询最多返回行数，交给 JDBC 驱动限制结果集 */
    private int maxRows = 1000;

    /** 单次查询数据库超时时间（秒） */
    private int queryTimeoutSeconds = 10;

    /** 方案⑦：是否启用自纠错循环（默认关；开=替代单次生成） */
    private boolean useSelfCorrection = false;

    /** 自纠错最大重试次数 */
    private int maxRetries = 3;

    /** 方案⑩：是否启用查询计划 Planner 两阶段生成（默认关，开启=多一次 LLM 调用，但准确率更高） */
    private boolean useQueryPlan = false;

    /** 是否在生成后真正执行 SQL（false=只生成不执行，安全预览） */
    private boolean execute = false;
}
