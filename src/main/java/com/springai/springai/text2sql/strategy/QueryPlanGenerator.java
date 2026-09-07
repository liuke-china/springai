package com.springai.springai.text2sql.strategy;

import com.springai.springai.text2sql.QueryPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 方案⑤（新增）：查询计划生成器（Planner）
 *
 * 【工作流】
 * 1. 把用户问题 + 已召回的 Schema/Few-shot/术语/JOIN/接口/业务口径 全部塞给 LLM
 * 2. 让 LLM 强制以 JSON 格式输出 QueryPlan（指标/维度/时间/表/JOIN/聚合）
 * 3. 解析失败时降级为空计划，不影响后续 SQL 生成（容错）
 *
 * 【为什么是企业级关键】
 * gopenai/AWS 的工业实践都强调"两阶段 Planner + Writer"：
 *   - Planner 专注"搞清楚要算什么"，context 小，错误率低
 *   - Writer（SqlGenerator）拿到 plan 当锚点，只写 SQL 语法
 *   - 出错时可单独回溯 plan 字段排查，不用回去看一长串 SQL
 *
 * 如果没有这一步：LLM 一次性写完一长串 SQL，错的时候很难定位"是维度错了还是 JOIN 错了"。
 */
@Slf4j
@Component
public class QueryPlanGenerator {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public QueryPlanGenerator(ChatClient.Builder builder, ObjectMapper objectMapper) {
        // Spring AI 1.1 只自动配置 ChatClient.Builder，不直接提供 ChatClient Bean
        // 这里跟其他生成器一致地按需 build()，不复用其他模块的实例
        this.chatClient = builder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * 让 LLM 写计划。失败时返回 null，不抛异常（保证编排链路不被阻断）。
     */
    public QueryPlan plan(String question, String schemaText, String glossarySection, String knowledgeSection) {
        if (question == null || question.isBlank()) return null;
        String prompt = buildPrompt(question, schemaText, glossarySection, knowledgeSection);
        try {
            String raw = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            return parsePlan(raw);
        } catch (Exception e) {
            log.warn("查询计划生成失败，降级为空计划: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 拼接 Prompt：只让 LLM 输出 JSON，不要解释文字。
     * 注意：明确告诉它维度/时间/表/JOIN 都要从已提供的证据里挑，不要瞎猜。
     */
    private String buildPrompt(String question, String schemaText, String glossarySection, String knowledgeSection) {
        return """
你是 NL2SQL 的查询规划器（Planner）。请把用户问题拆成结构化 JSON 计划。

【表结构候选（请只从这里挑表）】
%s

【业务术语对照（如 OEE、停机、稼动率）】
%s

【IMS 项目已知表关联 / 页面接口（如有）】
%s

【用户问题】
%s

【输出要求】
- 仅输出 JSON，不要解释、不要 markdown
- JSON 字段：metric/dimensions/timeRange/tables/joins/filters/aggregation/groupBy/ambiguities/confidence
- tables 只能选 schemaText 里出现的
- ambiguities 列出没把握的口径（"最近一个月=自然月还是30天？"）
- confidence 是 0-1，越确定越高

JSON：
""".formatted(
                schemaText == null ? "" : schemaText,
                glossarySection == null ? "" : glossarySection,
                knowledgeSection == null ? "" : knowledgeSection,
                question);
    }

    /**
     * 解析 LLM 输出：可能带 markdown ```json``` 包裹，先剥掉再 parse。
     */
    private QueryPlan parsePlan(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        // 截取第一个 { 到最后一个 }，防止 LLM 在 JSON 后追加尾巴
        int lb = trimmed.indexOf('{');
        int rb = trimmed.lastIndexOf('}');
        if (lb < 0 || rb < 0 || rb < lb) return null;
        String json = trimmed.substring(lb, rb + 1);
        try {
            return objectMapper.readValue(json, QueryPlan.class);
        } catch (Exception e) {
            log.debug("计划 JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }
}