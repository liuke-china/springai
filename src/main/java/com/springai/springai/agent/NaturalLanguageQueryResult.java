package com.springai.springai.agent;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Agent 的统一结果：既返回最终答案，也保留执行轨迹和"用过哪些工具"，方便看出它是 Agent。
 *
 * 新增两个字段（2026-08-05）：
 *   conversation   —— 本次任务中所有与 LLM 的交互（每轮一次 Q/A 字符串），格式 "Q: ...\nA: ..."（内部推理轨迹，调试用）
 *   conversationId —— 本次任务在 chat_memory 表里的会话ID，用来回查这条 Agent 的所有交互
 *   answer         —— 给前端的"干净回答"，格式固定 "Q: 原始问题\nA: 自然语言答案"，不含内部 JSON/thought
 */
public record NaturalLanguageQueryResult(
        boolean success,
        String sql,
        String explanation,
        List<String> tables,
        List<Map<String, Object>> rows,
        int steps,
        List<String> trace,
        List<String> toolsUsed,
        String errorMessage,
        List<String> conversation,
        String conversationId,
        String answer) {

    public static NaturalLanguageQueryResult success(String sql,
                                                      String explanation,
                                                      List<String> tables,
                                                      List<Map<String, Object>> rows,
                                                      int steps,
                                                      List<String> trace,
                                                      List<String> toolsUsed,
                                                      List<String> conversation,
                                                      String conversationId,
                                                      String answer) {
        return new NaturalLanguageQueryResult(
                true, sql, explanation,
                tables == null ? List.of() : List.copyOf(tables),
                rows == null ? List.of() : List.copyOf(rows),
                steps, List.copyOf(trace), List.copyOf(toolsUsed), null,
                conversation == null ? List.of() : List.copyOf(conversation),
                conversationId, answer);
    }

    public static NaturalLanguageQueryResult failure(String errorMessage,
                                                      int steps,
                                                      List<String> trace,
                                                      List<String> toolsUsed,
                                                      List<String> conversation,
                                                      String conversationId,
                                                      String answer) {
        return new NaturalLanguageQueryResult(
                false, null, null, Collections.emptyList(), Collections.emptyList(),
                steps, trace == null ? List.of() : List.copyOf(trace),
                toolsUsed == null ? List.of() : List.copyOf(toolsUsed), errorMessage,
                conversation == null ? List.of() : List.copyOf(conversation),
                conversationId, answer);
    }
}
