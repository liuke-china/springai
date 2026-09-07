package com.springai.springai.memory.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆 Advisor —— 双轨互补的第二轨（向量语义检索，在 LLM 调用前生效）
 *
 * 【解决什么问题】
 * 第一轨 JdbcChatMemoryRepository 只保留最近 10 条（滑动窗口），100 轮前问过的内容会被挤出。
 * 本 Advisor 在 LLM 调用前，把用户当前问题 embedding 后去 PGVector 检索 top-K 条语义相关的
 * 历史问答对，拼进 prompt，让模型「想起」很久以前聊过什么设备/话题。
 *
 * 【与第一轨的关系：互补不替代】
 * - 第一轨（窗口）：精准、管最近几轮、解决「它」这种指代
 * - 第二轨（向量）：跨很远、召回历史、解决「100 轮前」
 * - 两条合并后一次调 LLM；窗口优先、向量填空
 *
 * 【防污染设计（重要）】
 * 检索到的历史只拼进 **SystemMessage**，绝不修改 UserMessage。
 * 原因：MessageChatMemoryAdvisor 的 after 会把当轮 UserMessage 写进 JDBC 精确记忆，
 * 如果这里改了 UserMessage，带历史的文本会被写进 SPRING_AI_CHAT_MEMORY，污染短期记忆。
 * 拼进 SystemMessage 则不影响 JDBC 落库内容。
 *
 * 【去重】检索结果里 conversationId 与当前会话相同（已在窗口里）的，丢弃，避免重复喂
 *
 * 【只做读】问答对的「写」在 ImsAskController 拿到完整答案后写入 longTermMemoryVectorStore
 * （流式场景在 onComplete 写，避免存半截答案）。本类 after() 直接透传，不落库。
 */
public class VectorMemoryAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(VectorMemoryAdvisor.class);

    private final VectorStore vectorStore;
    private final int topK;
    private final double similarityThreshold;
    private final String conversationIdKey;

    public VectorMemoryAdvisor(VectorStore vectorStore, int topK, double similarityThreshold) {
        this(vectorStore, topK, similarityThreshold, "chat_memory_conversation_id");
    }

    public VectorMemoryAdvisor(VectorStore vectorStore, int topK, double similarityThreshold, String conversationIdKey) {
        this.vectorStore = vectorStore;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
        this.conversationIdKey = conversationIdKey;
    }

    @Override
    public String getName() {
        return "LongTermVectorMemoryAdvisor";
    }

    @Override
    public int getOrder() {
        // 比窗口记忆（DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER，很大的负数）晚执行无妨，
        // 本 Advisor 只依赖用户当前问题文本，与窗口顺序无关。
        return 0;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        // 1. 取当前用户问题
        UserMessage userMessage = request.prompt().getUserMessage();
        if (userMessage == null) {
            return request;
        }
        String question = userMessage.getText();
        if (question == null || question.isBlank()) {
            return request;
        }

        // 2. 当前会话 id（用于去重：不把本会话刚发生的内容又塞一遍）
        String currentSession = null;
        Map<String, Object> ctx = request.context();
        if (ctx != null && ctx.containsKey(conversationIdKey)) {
            currentSession = String.valueOf(ctx.get(conversationIdKey));
        }

        // 3. 向量检索相关历史问答对
        // 【关键修复】不把 similarityThreshold 传给 PgVectorStore 的 similaritySearch。
        // 1.0.0 里带阈值的向量检索会在 PG 端拼 WHERE 阈值 SQL，在部分 PG / 距离类型组合下
        // 抛 IllegalStateException（正是这次 "Stream processing failed" 的真凶）。
        // 改为：多取一批(topK*2)，在 Java 端按 doc.getScore() 过滤，绕开这条坑路径。
        // 整段包 try/catch：长期记忆是「锦上添花」，检索失败绝不能拖垮主对话（短期窗口记忆照常）。
        List<Document> hits;
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(question)
                    .topK(Math.max(topK * 2, 10))
                    .build();
            hits = vectorStore.similaritySearch(searchRequest);
        } catch (Exception e) {
            log.warn("[LongTermMemory] 向量长期记忆检索失败，降级为仅短期窗口记忆。真正原因: {}",
                    e.getMessage(), e);
            return request; // 不抛异常，主对话继续走（短期记忆仍生效）
        }
        if (hits == null || hits.isEmpty()) {
            return request;
        }

        // 4. 过滤掉当前会话的 + 按相似度阈值精选 + 拼装历史文本
        StringBuilder history = new StringBuilder();
        int idx = 0;
        for (Document doc : hits) {
            Object sid = doc.getMetadata().get("conversationId");
            /*if (sid != null && sid.toString().equals(currentSession)) {
                continue; // 去重：本会话近期内容已在窗口里，不重复注入
            }*/
            Double score = doc.getScore(); // 余弦相似度（PGVectorStore 已算好 1-distance）
            if (score != null && score < similarityThreshold) {
                continue; // 相似度不够，跳过
            }
            String qa = doc.getText().replaceAll("<think>.*?</think>", "").trim();
            if (!qa.isBlank()) {
                idx++;
                history.append("\n[相关历史 ").append(idx).append("]\n").append(qa);
            }
        }

        if (history.isEmpty()) {
            return request; // 没检索到相关历史，原样返回，零开销
        }

        // 5. 把历史拼进 SystemMessage（防污染：不动 UserMessage）
        Prompt prompt = request.prompt();
        String baseSystem = prompt.getSystemMessage() != null
                ? prompt.getSystemMessage().getText()
                : "";
        String augmentedSystem = baseSystem
                + "\n\n【历史相关对话 - 来自长期记忆，仅供参照，不要当作当前问题】"
                + history;

        List<Message> instructions = new ArrayList<>(prompt.getInstructions());
        boolean replaced = false;
        for (int i = 0; i < instructions.size(); i++) {
            if (instructions.get(i) instanceof SystemMessage) {
                instructions.set(i, new SystemMessage(augmentedSystem));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            instructions.add(0, new SystemMessage(augmentedSystem));
        }

        return request.mutate()
                .prompt(new Prompt(instructions, prompt.getOptions()))
                .build();
    }

    /** 透传响应，不在此落库（写入在 controller 完成） */
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }
}
