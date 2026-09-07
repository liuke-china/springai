package com.springai.springai.text2sql;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 结果摘要（Result Summarizer）—— LLM 把"表格行"翻译成自然语言。
 *
 * 【为什么需要】
 * 用户拿到的如果是几十行原始 Map，看不出业务含义；
 * 让 LLM 看一遍数据，生成 1-2 句自然语言总结，UI 直接展示，体感接近 BI 工具。
 *
 * 【企业用法】
 * 类似 Tableau / PowerBI 的"智能洞察"模块：用户给问题，系统给图表 + 一段解释。
 *
 * 【成本控制】
 * 默认对前 10 行做摘要，超过 10 行的数据按"是否需要"分批；本次实现简单截断。
 */
@Slf4j
@Component
public class ResultSummarizer {

    private final ChatClient chatClient;

    public ResultSummarizer(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * 把数据行翻译成一句话总结。
     * @param question 用户问题
     * @param rows     实际查询返回的数据行
     * @return 自然语言总结；失败时降级为字符串拼接
     */
    public String summarize(String question, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return "查询未返回任何数据。";
        // 截断：前 10 行足够让 LLM 看出规律；太长会增加 token 成本
        int max = Math.min(rows.size(), 10);
        StringBuilder data = new StringBuilder();
        for (int i = 0; i < max; i++) {
            data.append(i + 1).append(") ").append(rows.get(i)).append("\n");
        }
        String prompt = """
你是数据分析助手。用户问：%s
查询返回了 %d 行（前 %d 行）：
%s

请用 1-3 句中文总结关键信息（如总数、最大/最小、趋势、异常点）。
要求：直接给结论，不要重复数据本身，不要说"根据以上数据"。
""".formatted(
                question == null ? "" : question,
                rows.size(), max, data);
        try {
            return chatClient.prompt().user(prompt).call().content();
        } catch (Exception e) {
            log.warn("结果摘要生成失败，返回 fallback: {}", e.getMessage());
            // fallback：简单拼接，避免阻塞调用方
            return String.format("查询共 %d 行数据；首行字段：%s。",
                    rows.size(), rows.get(0).keySet());
        }
    }
}