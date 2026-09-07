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
 *   1) 内存记忆（InMemoryChatMemoryRepository）：重启项目历史即丢，作对照
 *   2) PG 向量库记忆：聊天记录存进 PostgreSQL 向量表，重启不丢，可从 PG 拿回
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

    // ==================== 1. 内存记忆（重启即丢） ====================
    /**
     * 多轮对话，传相同 conversationId 即可记忆
     * 注意：记忆存在 JVM 内存，重启项目历史就没了
     * 测试：/ai/memory/chat?conversationId=demo&prompt=我叫小明  →  /ai/memory/chat?conversationId=demo&prompt=我叫什么
     */
    @GetMapping("/ai/memory/chat")
    public String inMemoryChat(@RequestParam String prompt,
                               @RequestParam(defaultValue = "demo") String conversationId) {
        return inMemoryChatClient.prompt(prompt)
                .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                .call()
                .content();
    }

    // ==================== 2. 查看某会话内存历史 ====================
    /**
     * 测试：/ai/memory/history?conversationId=demo
     */
    @GetMapping("/ai/memory/history")
    public Map<String, Object> inMemoryHistory(@RequestParam(defaultValue = "demo") String conversationId) {
        return Map.of("conversationId", conversationId, "messages", chatMemory.get(conversationId));
    }

    // ==================== 3. 清空某会话内存记忆 ====================
    /**
     * 测试：/ai/memory/clear?conversationId=demo
     */
    @GetMapping("/ai/memory/clear")
    public Map<String, Object> inMemoryClear(@RequestParam(defaultValue = "demo") String conversationId) {
        chatMemory.clear(conversationId);
        return Map.of("conversationId", conversationId, "cleared", true);
    }

    // ==================== 4. PG 向量库记忆（重启不丢） ====================
    /**
     * 多轮对话，聊天记录存进 PG 向量表（vector_store），按 conversationId 隔离
     * 下一轮先按 conversationId 从 PG 取回历史拼进上下文再回答，所以重启项目也不丢
     * 测试：/ai/pgmemory/chat?conversationId=demo&prompt=我叫小明  →  /ai/pgmemory/chat?conversationId=demo&prompt=我叫什么
     *       重启项目后再问一次，仍能记得（数据在 PG，不在内存）
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

    // ==================== 4.从 PG 向量表拿回某会话全部聊天记录 ====================
    /**
     * 测试：/ai/pgmemory/history?conversationId=demo
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

    // ==================== 5.清空某会话在 PG 向量表的聊天记录 ====================
    /**
     * 清空某会话在 PG 向量表的聊天记录
     * 测试：/ai/pgmemory/clear?conversationId=demo
     */
    @GetMapping("/ai/pgmemory/clear")
    public Map<String, Object> pgClear(@RequestParam(defaultValue = "demo") String conversationId) {
        Filter.Expression filter = new FilterExpressionBuilder().eq("conversationId", conversationId).build();
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query("").topK(1000).filterExpression(filter).build());
        vectorStore.delete(docs.stream().map(Document::getId).toList());
        return Map.of("conversationId", conversationId, "deleted", docs.size());
    }

    // ==================== 6. 企业级记忆：时间窗口 + 语义检索双路 ====================
    /**
     * 企业级多轮记忆：最近5条（时间窗口）+ 语义检索最相关3条（RAG 风格），合并去重
     * 比纯内存/纯向量更稳：短期连续性 + 长期相关性都覆盖
     * 测试：/ai/pgmemory/chatPro?conversationId=demo&prompt=...
     * 注意：跨会话长期记忆去掉 conversationId filter 即可（全局向量库检索）
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
