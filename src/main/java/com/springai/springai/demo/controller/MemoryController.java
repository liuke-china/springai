package com.springai.springai.demo.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring AI 记忆演示
 *
 * 【两套记忆对照】
 * 1) 内存记忆（InMemoryChatMemoryRepository）：重启项目历史即丢，作对照
 * 2) PG 向量库记忆：聊天记录存进 PostgreSQL 向量表，重启不丢，可从 PG 拿回
 *
 * 【Postman 对应】
 * 集合：demo.postman_collection.json（resources）
 * 分组：「4. 会话记忆」共 7 个接口，按 memory（内存）→ pgmemory（PG 持久）两段排列
 *      记忆效果测法：chat 传同一 conversationId 先说"我叫小明"再问"我叫什么"
 */
@RestController
public class MemoryController {

    private final ChatClient inMemoryChatClient;
    private final ChatClient plainChatClient;
    private final ChatMemory chatMemory;
    private final VectorStore vectorStore;

    public MemoryController(ChatModel chatModel,
                           @Qualifier("pgVectorStore") VectorStore vectorStore) {
        // 第1部分：内存记忆（重启即丢，作对照）
        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .build();
        this.inMemoryChatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
        // 第2部分：纯 ChatModel，不带记忆 Advisor，记忆自己用 PG 向量库管
        this.plainChatClient = ChatClient.builder(chatModel).build();
        this.vectorStore = vectorStore;
    }

    // ==================== 1. 内存记忆：多轮对话 ====================

    /**
     * 演示：官方 Advisor 方式挂记忆——传相同 conversationId 即可续上对话
     * Postman：分组「4. 会话记忆」→ memory/chat
     * 示例请求：GET /ai/memory/chat?conversationId=demo&prompt=我叫小明
     *          再调 GET /ai/memory/chat?conversationId=demo&prompt=我叫什么 → AI 能答出"小明"
     *
     * 流程：MessageChatMemoryAdvisor 在请求前按 conversationId 取窗口内历史拼进 prompt
     * 注意：记忆存在 JVM 内存（InMemoryChatMemoryRepository），重启项目历史就没了
     */
    @GetMapping("/ai/memory/chat")
    public String inMemoryChat(@RequestParam String prompt,
                               @RequestParam(defaultValue = "demo") String conversationId) {
        return inMemoryChatClient.prompt(prompt)
                .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                .call()
                .content();
    }

    // ==================== 2. 内存记忆：查看历史 ====================

    /**
     * 演示：直接读 chatMemory，看某会话存了哪几条消息
     * Postman：分组「4. 会话记忆」→ memory/history
     * 示例请求：GET /ai/memory/history?conversationId=demo
     *
     * 用途：配合 memory/chat 验证"刚才的对话确实进了内存记忆"
     */
    @GetMapping("/ai/memory/history")
    public Map<String, Object> inMemoryHistory(@RequestParam(defaultValue = "demo") String conversationId) {
        return Map.of("conversationId", conversationId, "messages", chatMemory.get(conversationId));
    }

    // ==================== 3. 内存记忆：清空会话 ====================

    /**
     * 演示：清空某会话的内存记忆，清完再问"我叫什么"AI 就不知道了
     * Postman：分组「4. 会话记忆」→ memory/clear
     * 示例请求：GET /ai/memory/clear?conversationId=demo
     */
    @GetMapping("/ai/memory/clear")
    public Map<String, Object> inMemoryClear(@RequestParam(defaultValue = "demo") String conversationId) {
        chatMemory.clear(conversationId);
        return Map.of("conversationId", conversationId, "cleared", true);
    }

    // ==================== 4. PG 向量库记忆：多轮对话（重启不丢） ====================

    /**
     * 演示：不用官方记忆 Advisor，聊天记录当"文档"存进 PG 向量表，自己管记忆
     * Postman：分组「4. 会话记忆」→ pgmemory/chat
     * 示例请求：GET /ai/pgmemory/chat?conversationId=demo&prompt=我叫小明
     *          再调一次问"我叫什么" → AI 能答出；重启项目后再问，仍能答出（数据在 PG 不在内存）
     *
     * 流程：按 conversationId 过滤检索历史 → 按时间排序拼上下文 → 调模型 → 本轮 Q/A 写回向量表
     * 关键点：metadata 存 role（user/assistant）+ time，检索时用 FilterExpression 过滤会话
     * 场景：与 memory/chat 对比重启效果，这是"把向量库当记忆存储"的思路演示
     */
    @GetMapping("/ai/pgmemory/chat")
    public String pgChat(@RequestParam String prompt,
                         @RequestParam(defaultValue = "demo") String conversationId) {
        // 1. 从 PG 向量表取回本会话历史
        Filter.Expression filter = new FilterExpressionBuilder().eq("conversationId", conversationId).build();
        List<Document> history = vectorStore.similaritySearch(
                SearchRequest.builder().query(prompt).topK(50).filterExpression(filter).build());
        history.sort(Comparator.comparing(d -> String.valueOf(d.getMetadata().get("time"))));
        // 2. 组装历史上下文
        StringBuilder context = new StringBuilder();
        for (Document d : history) {
            context.append(d.getMetadata().get("role")).append(": ").append(d.getText()).append("\n");
        }
        // 3. 调模型（有历史就带上历史）
        String answer = context.length() == 0
                ? plainChatClient.prompt(prompt).call().content()
                : plainChatClient.prompt()
                    .system("以下是之前的对话记录，请结合上下文回答：\n" + context)
                    .user(prompt)
                    .call().content();
        // 4. 把本轮 user + assistant 都存进 PG 向量表
        long now = System.currentTimeMillis();
        vectorStore.add(List.of(
                new Document(prompt, Map.of("conversationId", conversationId, "role", "user", "time", String.valueOf(now))),
                new Document(answer, Map.of("conversationId", conversationId, "role", "assistant", "time", String.valueOf(now + 1)))
        ));
        return answer;
    }

    // ==================== 5. PG 向量库记忆：拿回全部聊天记录 ====================

    /**
     * 演示：从 PG 向量表按会话捞回全部记录（role/content/time），验证持久化确实生效
     * Postman：分组「4. 会话记忆」→ pgmemory/history
     * 示例请求：GET /ai/pgmemory/history?conversationId=demo
     *
     * 用途：重启项目后调这个，能看到重启前的聊天记录还在
     */
    @GetMapping("/ai/pgmemory/history")
    public List<Map<String, Object>> pgHistory(@RequestParam(defaultValue = "demo") String conversationId) {
        Filter.Expression filter = new FilterExpressionBuilder().eq("conversationId", conversationId).build();
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query("").topK(100).filterExpression(filter).build());
        docs.sort(Comparator.comparing(d -> String.valueOf(d.getMetadata().get("time"))));
        return docs.stream().map(d -> Map.of(
                "role", d.getMetadata().get("role"),
                "content", d.getText(),
                "time", d.getMetadata().get("time")
        )).toList();
    }

    // ==================== 6. PG 向量库记忆：清空会话记录 ====================

    /**
     * 演示：把某会话在 PG 向量表里的聊天记录全部删掉
     * Postman：分组「4. 会话记忆」→ pgmemory/clear
     * 示例请求：GET /ai/pgmemory/clear?conversationId=demo
     *
     * 流程：按 conversationId 检索出全部记录 → 取 id 批量 delete → 返回删除条数
     */
    @GetMapping("/ai/pgmemory/clear")
    public Map<String, Object> pgClear(@RequestParam(defaultValue = "demo") String conversationId) {
        Filter.Expression filter = new FilterExpressionBuilder().eq("conversationId", conversationId).build();
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query("").topK(1000).filterExpression(filter).build());
        vectorStore.delete(docs.stream().map(Document::getId).toList());
        return Map.of("conversationId", conversationId, "deleted", docs.size());
    }

    // ==================== 7. 企业级记忆：时间窗口 + 语义检索双路 ====================

    /**
     * 演示：企业级多轮记忆——最近 5 条（时间窗口）+ 语义检索最相关 3 条（RAG 风格），合并去重
     * Postman：分组「4. 会话记忆」→ pgmemory/chatPro
     * 示例请求：GET /ai/pgmemory/chatPro?conversationId=demo&prompt=我叫什么名字
     *          （传一个只跟早期对话相关的 prompt，能看出语义路把时间窗口外的旧对话捞回来了）
     *
     * 流程：全量历史按时间排序 → 取最近 5 条 + 向量检索最相关 3 条 → 按 id 去重合并 → 拼上下文调模型 → 存回本轮
     * 为什么这么写：纯时间窗口会丢"很久之前但相关"的记忆，纯语义检索会丢"刚刚说的连续上下文"，双路互补
     * 注意：跨会话长期记忆去掉 conversationId 过滤即可（全局向量库检索）
     */
    @GetMapping("/ai/pgmemory/chatPro")
    public String pgChatPro(@RequestParam String prompt,
                            @RequestParam(defaultValue = "demo") String conversationId) {
        Filter.Expression filter = new FilterExpressionBuilder().eq("conversationId", conversationId).build();
        // 1. 本会话全部历史，按时间排序
        List<Document> all = vectorStore.similaritySearch(
                SearchRequest.builder().query("").topK(1000).filterExpression(filter).build());
        all.sort(Comparator.comparing(d -> String.valueOf(d.getMetadata().get("time"))));
        // 2. 时间窗口：最近5条
        List<Document> recent = all.stream().skip(Math.max(0, all.size() - 5)).toList();
        // 3. 语义检索：与当前问题最相关的3条（RAG 风格）
        List<Document> related = vectorStore.similaritySearch(
                SearchRequest.builder().query(prompt).topK(3).filterExpression(filter).build());
        // 4. 合并去重（时间窗口在前，语义相关补充在后）
        LinkedHashMap<String, Document> merged = new LinkedHashMap<>();
        recent.forEach(d -> merged.put(d.getId(), d));
        related.forEach(d -> merged.putIfAbsent(d.getId(), d));
        StringBuilder context = new StringBuilder();
        for (Document d : merged.values()) {
            context.append(d.getMetadata().get("role")).append(": ").append(d.getText()).append("\n");
        }
        // 5. 调模型
        String answer = context.length() == 0
                ? plainChatClient.prompt(prompt).call().content()
                : plainChatClient.prompt()
                    .system("以下是相关的历史对话，请结合上下文回答：\n" + context)
                    .user(prompt)
                    .call().content();
        // 6. 存本轮
        long now = System.currentTimeMillis();
        vectorStore.add(List.of(
                new Document(prompt, Map.of("conversationId", conversationId, "role", "user", "time", String.valueOf(now))),
                new Document(answer, Map.of("conversationId", conversationId, "role", "assistant", "time", String.valueOf(now + 1)))
        ));
        return answer;
    }
}
