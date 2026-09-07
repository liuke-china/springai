package com.springai.springai.text2sql.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * L2 提示词模板集中管理（四段式：角色 / 任务段标题 / 约束 / 输出）。
 *
 * 【为什么有这个类】
 * 原来所有"写给 AI 看的固定指令"散落在 SqlGenerator 的 sb.append 字符串里，
 * 改一条规则要翻代码、无法单条 A/B、没有评测。现在全部集中到这里：
 *   - 改 prompt 文案 → 只动这一个文件，全局生效
 *   - 想做 L3 评测（单条开关看效果）→ 常量可直接被测试引用
 *
 * 【四段式结构】
 *   ① 角色段 ROLE
 *   ② 任务段（各动态段标题 SECTION_*，运行时由各 Strategy 填充内容）
 *   ③ 约束段 RULE_1~RULE_10（固定 1-5 + 10，条件 6-9）
 *   ④ 输出段 OUTPUT_FORMAT + SYSTEM_JSON_ONLY
 */
public final class PromptTemplate {

    private PromptTemplate() {}

    // ===================== System（输出格式锁死，消除 SqlGenerator 两处重复）=====================
    public static final String SYSTEM_JSON_ONLY =
            "你只返回JSON，不要返回任何其他文字。不要加markdown代码块标记。";

    // ===================== ① 角色段（Role）=====================
    public static final String ROLE =
            "你是 PostgreSQL 数据库专家。根据以下表结构，为用户需求生成 SQL。";

    // ===================== ② 任务段标题（各动态段由 Strategy 注入内容）=====================
    public static final String SECTION_SCHEMA = "【数据库表结构】";
    public static final String SECTION_GLOSSARY = "【业务术语与字段枚举字典】";
    public static final String SECTION_FEWSHOT = "【参考范例】";
    public static final String SECTION_COT = "【思维链】";
    public static final String SECTION_KNOWLEDGE = "【已知表关联】与【已知页面/接口】";
    public static final String SECTION_WHERE_HINT = "【各表常用过滤字段】";
    public static final String SECTION_USER_NEED = "【用户需求】";
    public static final String SECTION_RULES = "【严格规则】";
    public static final String SECTION_REFLECTION = "【反思】";
    public static final String SECTION_OUTPUT = "【返回格式】";

    // ===================== ③ 约束段（Constraints）=====================
    // --- 固定约束（无条件追加）---
    public static final String RULE_1 =
            "1. 只生成 SELECT 查询，绝对禁止 DELETE/UPDATE/DROP/INSERT/TRUNCATE/ALTER/CREATE";
    public static final String RULE_2 =
            "2. 必须加 LIMIT，默认最多返回 1000 条";
    public static final String RULE_3 =
            "3. 表名和字段名不要加双引号，直接写裸标识符（如 device、device_operation_report、state）；IMS 表名与字段名均为小写，无需引号";
    public static final String RULE_4 =
            "4. 生成的 SQL 必须写成单行，不要换行，各子句之间只用一个空格分隔";
    public static final String RULE_5 =
            "5. 只使用上面列出的表和字段，不要编造不存在的表";
    /** RULE_10：根治"相对时间"bug——把之前靠 where_hint 提示的绝对区间写法升级为固定硬约束。 */
    public static final String RULE_10 =
            "10. 时间条件一律使用绝对区间写法（如 create_time >= '2026-08-10 00:00:00' AND create_time <= '2026-08-10 23:59:59'），" +
            "禁止使用相对 INTERVAL 或 '7 day' 之类的偏移";


    // --- 条件约束（对应动态段存在时才追加）---
    public static final String RULE_6_FEWSHOT =
            "6. 参考上方【参考范例】的写法风格与表/字段用法，按本问题改写，不要原样照搬。";
    public static final String RULE_7_GLOSSARY =
            "7. 严格按照上方【业务术语与字段枚举字典】理解术语与枚举值，禁止臆测。";
    public static final String RULE_8_KNOWLEDGE =
            "8. 已知 JOIN 模式 / 已知页面-表映射已在【已知表关联】与【已知页面/接口】给出，照抄其 JOIN 写法即可。";
    public static final String RULE_9_WHERE_HINT =
            "9. 【各表常用过滤字段】是 IMS 源码真实查询模式，生成 WHERE 时优先参考这些字段与运算；" +
            "但仍是参考而非强制，若业务语义明显指向其它列，以语义为准。";

    // ===================== ④ 输出段（Structured Output）=====================
    public static final String OUTPUT_FORMAT =
            "{\"sql\":\"SELECT ...\",\"explanation\":\"...\",\"tables\":[\"...\"],\"riskLevel\":\"SAFE\",\"riskNote\":\"...\"}";

    // ===================== 思维链（CoT）原文 =================
    // 原 CoTStrategy 整个类的内容物；启用时由 SqlGenerator 拼到 Prompt，不再有独立策略类。
    public static final String COT_BODY = """
            【思维链推理要求】(Chain-of-Thought)
            在输出最终 SQL 之前，请严格按以下顺序在内部逐步推理（不要在 JSON 中输出推理过程，但必须遵循）：
            1. 识别本问题涉及哪些表（只从上方【数据库表结构】中选择，禁止编造）
            2. 确定这些表之间的 JOIN 关系与连接键
            3. 确定 WHERE 过滤条件（含时间范围、状态枚举、设备筛选等）
            4. 确定是否需要聚合(GROUP BY / 聚合函数)、排序(ORDER BY)、分页(LIMIT)
            5. 综合以上，输出最终 SQL
            遵循"先想清楚再写"的原则，可显著提升复杂多表查询的准确率。
            """;

    // ===================== 反思段前缀（统一 SelfCorrection 双套文案，消除硬编码）=====================
    public static final String REFLECTION_SAFETY_PREFIX = "【之前被安全护栏拦截，请重新生成纯 SELECT 查询】";
    public static final String REFLECTION_ERROR_PREFIX = "【之前的错误历史（请反思并修正）】";
    public static final String REFLECTION_TAIL = "请基于以上错误反思，重新生成正确的 SQL。";

    // ===================== L3 评测：按"禁用规则集合"决定追加哪些规则 =====================
    /**
     * 根据各动态段是否出现 + 禁用集合，返回最终应追加到 Prompt 的规则行列表（保序）。
     *
     * @param fewShot   是否注入了参考范例段（决定条件规则 6 是否可能追加）
     * @param glossary  是否注入了术语字典段（决定条件规则 7）
     * @param knowledge 是否注入了 IMS 专属知识段（决定条件规则 8）
     * @param whereHint 是否注入了各表过滤字段段（决定条件规则 9）
     * @param disabledRules 要关闭的规则编号集合；null 或空集 = 全部启用
     * @return 有序的规则文本列表（每行已含编号前缀，如 "1. 只生成 SELECT..."）
     */
    public static List<String> buildRuleLines(boolean fewShot, boolean glossary,
                                              boolean knowledge, boolean whereHint,
                                              Set<Integer> disabledRules) {
        Set<Integer> off = (disabledRules == null) ? Set.of() : disabledRules;
        List<String> out = new ArrayList<>();
        addIf(out, 1, RULE_1, off);
        addIf(out, 2, RULE_2, off);
        addIf(out, 3, RULE_3, off);
        addIf(out, 4, RULE_4, off);
        addIf(out, 5, RULE_5, off);
        addIf(out, 10, RULE_10, off);
        if (fewShot)   addIf(out, 6, RULE_6_FEWSHOT, off);
        if (glossary)  addIf(out, 7, RULE_7_GLOSSARY, off);
        if (knowledge) addIf(out, 8, RULE_8_KNOWLEDGE, off);
        if (whereHint) addIf(out, 9, RULE_9_WHERE_HINT, off);
        return out;
    }

    private static void addIf(List<String> out, int num, String rule, Set<Integer> off) {
        if (!off.contains(num)) out.add(rule);
    }
}
