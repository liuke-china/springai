package com.springai.springai.text2sql.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springai.springai.demo.entity.SqlResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 生成器（粘合各策略产物的最后一步）—— 组合 Schema / Glossary / Few-shot / CoT 各段，调 LLM 生成 SQL。
 *
 * 【做什么】
 *   把各策略产出的文本段拼成最终 Prompt，调 ChatClient 出 SQL。
 *
 * 【不做什么】
 *   不负责任何召回 / 校验 / 重试；这些都在 Orchestrator / SafetyGuardStrategy / SelfCorrectionStrategy 里。
 *   这样"Prompt 怎么拼"和"召回/校验/重试"彻底解耦，改一个不影响其他。
 *
 * 【主要 API】
 *   generate(...)           主入口（9 参，含 disabledRules）
 *   generate(...)           8 参版：内部转 9 参（disabledRules=null）
 *   generate(...)           6 参旧版：转 9 参（knowledge/whereHint 都为空），@Deprecated
 *
 * 【小模型兜底】
 *   大模型走结构化 entity(SqlResult.class) 解析；失败时降级为 content() + JSON 正则提取。
 */
@Component
public class SqlGenerator {

    private final ChatClient chatClient;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public SqlGenerator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 生成 SQL（支持任意组合的附加段 + 错误反思段）。
     *
     * @param schemaText       表结构(DDL)文本（来自 SchemaRetrievalStrategy）
     * @param question         用户问题
     * @param fewShotSection   few-shot 范例段（可为空）
     * @param cotSection       思维链提示段（可为空）
     * @param glossarySection  业务术语字典段（可为空）
     * @param knowledgeSection IMS 项目专属知识段（已知表关联 + 已知页面/接口，可为空）
     * @param reflectionSection 历史错误反思段（自纠错循环用，首轮为空）
     * @return SqlResult
     */
    public SqlResult generate(String schemaText, String question,
                              String fewShotSection, String cotSection,
                              String glossarySection, String knowledgeSection,
                              String whereHintSection, String reflectionSection) {
        return generate(schemaText, question, fewShotSection, cotSection, glossarySection,
                knowledgeSection, whereHintSection, reflectionSection, null);
    }

    /**
     * 主入口：调 LLM 生成 SQL，自动套用结构化输出 + JSON 提取兜底。
     *
     * @param disabledRules 关掉的规则编号（用于 L3 消融测试；空集合 = 全部启用）
     */
    public SqlResult generate(String schemaText, String question,
                              String fewShotSection, String cotSection,
                              String glossarySection, String knowledgeSection,
                              String whereHintSection, String reflectionSection,
                              Set<Integer> disabledRules) {
        String prompt = buildPrompt(schemaText, question, fewShotSection, cotSection, glossarySection, knowledgeSection, whereHintSection, reflectionSection, disabledRules);
        try {
            // 大模型：一步结构化解析
            SqlResult r = chatClient.prompt()
                    .system(PromptTemplate.SYSTEM_JSON_ONLY)
                    .user(prompt)
                    .call()
                    .entity(SqlResult.class);
            return sanitize(r);
        } catch (Exception e) {
            // 小模型兼容：拿原始文本手动提取 JSON
            try {
                String rawText = chatClient.prompt()
                        .system(PromptTemplate.SYSTEM_JSON_ONLY)
                        .user(prompt)
                        .call()
                        .content();
                return sanitize(extractJsonFromText(rawText));
            } catch (Exception ex) {
                throw new RuntimeException("AI 生成 SQL 失败，请换个说法重试。原因: " + ex.getMessage(), ex);
            }
        }
    }

    /**
     * 旧签名兼容（无 knowledge/whereHint 段），内部转发到主入口。
     * 仅供旧调用方过渡使用，新代码请直接调 9 参版本。
     */
    @Deprecated
    public SqlResult generate(String schemaText, String question,
                              String fewShotSection, String cotSection,
                              String glossarySection, String reflectionSection) {
        return generate(schemaText, question, fewShotSection, cotSection, glossarySection, "", "", reflectionSection, null);
    }

    /**
     * 纯字符串拼接 Prompt（避免 SQL/范例里含 % 触发 String.format 异常）。
     * 各段顺序：角色+表结构 → 业务字典 → 参考范例 → 思维链 → IMS 专属知识 → 用户需求 → 严格规则 → 反思 → 返回格式
     * 规则段由 PromptTemplate.buildRuleLines 按 disabledRules 决定，支持 L3 单条开关评测。
     */
    private String buildPrompt(String schemaText, String question,
                               String fewShotSection, String cotSection,
                               String glossarySection, String knowledgeSection,
                               String whereHintSection, String reflectionSection,
                               Set<Integer> disabledRules) {
        boolean hasFewShot = fewShotSection != null && !fewShotSection.isEmpty();
        boolean hasGlossary = glossarySection != null && !glossarySection.isEmpty();
        boolean hasKnowledge = knowledgeSection != null && !knowledgeSection.isEmpty();
        boolean hasWhereHint = whereHintSection != null && !whereHintSection.isEmpty();

        StringBuilder sb = new StringBuilder();
        sb.append(PromptTemplate.ROLE).append("\n\n");
        sb.append(PromptTemplate.SECTION_SCHEMA).append("\n").append(schemaText).append("\n");

        if (hasGlossary) {
            sb.append("\n").append(glossarySection).append("\n");
        }
        if (hasFewShot) {
            sb.append("\n").append(fewShotSection).append("\n");
        }
        if (cotSection != null && !cotSection.isEmpty()) {
            sb.append("\n").append(cotSection).append("\n");
        }
        if (hasKnowledge) {
            // 方案⑧：追加在 schema 段之后、用户需求之前；不覆盖任何已有段。
            sb.append("\n").append(knowledgeSection).append("\n");
        }
        if (hasWhereHint) {
            // 方案⑨：各表常用过滤字段（源码先验），紧跟 knowledge 之后、用户需求之前
            sb.append("\n").append(whereHintSection).append("\n");
        }

        sb.append("\n").append(PromptTemplate.SECTION_USER_NEED).append("\n").append(question).append("\n\n");

        sb.append(PromptTemplate.SECTION_RULES).append("\n");
        for (String rule : PromptTemplate.buildRuleLines(hasFewShot, hasGlossary, hasKnowledge, hasWhereHint, disabledRules)) {
            sb.append(rule).append("\n");
        }

        if (reflectionSection != null && !reflectionSection.isEmpty()) {
            sb.append("\n").append(reflectionSection).append("\n");
        }

        sb.append("\n").append(PromptTemplate.SECTION_OUTPUT)
          .append("只返回JSON，不要任何其他文字，不要markdown代码块：\n");
        sb.append(PromptTemplate.OUTPUT_FORMAT).append("\n");
        return sb.toString();
    }

    /**
     * 从 AI 返回文本中手动提取 JSON 并解析为 SqlResult（兼容小模型在 JSON 前后加多余文字）。
     */
    private SqlResult extractJsonFromText(String text) throws Exception {
        String cleaned = text.replaceAll("```json\\s*", "").replaceAll("```", "").trim();
        Pattern pattern = Pattern.compile("\\{.*}");
        Matcher matcher = pattern.matcher(cleaned);
        if (matcher.find()) {
            return OBJECT_MAPPER.readValue(matcher.group(), SqlResult.class);
        }
        throw new RuntimeException("无法从 AI 回复中提取 JSON: " + text);
    }

    private static final Pattern IDENTIFIER_QUOTE = Pattern.compile("\"([A-Za-z_][A-Za-z0-9_]*)\"");

    /**
     * 清洗 SQL 结果：压缩换行/多余空格 + 去除标识符双引号（IMS 表名全小写，无需引号）。
     * 同时清洗 tables 数组里的引号。所有生成链路（/generate /trace /query /query-react）共用此切点。
     */
    private SqlResult sanitize(SqlResult r) {
        if (r == null) return null;
        if (r.getSql() != null) {
            r.setSql(cleanSql(r.getSql()));
        }
        if (r.getTables() != null) {
            r.getTables().replaceAll(t -> t == null ? null : t.replace("\"", "").trim());
        }
        return r;
    }

    private String cleanSql(String sql) {
        if (sql == null) return null;
        String s = sql.replace('\n', ' ').replace('\r', ' ');
        s = s.replaceAll("\\s+", " ");
        s = IDENTIFIER_QUOTE.matcher(s).replaceAll("$1");
        return s.trim();
    }
}
