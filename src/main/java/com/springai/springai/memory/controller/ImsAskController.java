package com.springai.springai.memory.controller;

import com.springai.springai.memory.memory.VectorMemoryAdvisor;
import com.springai.springai.memory.tool.ImsDeviceTool;
import com.springai.springai.safe.OutputSanitizer;
import com.springai.springai.safe.SecurityAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * IMS 设备自然语言查询（流式输出 + 多轮对话）
 *
 * 端点：
 * - GET  /ai/ims/ask/stream?question=...&sessionId=...   SSE 流式
 * - GET  /ai/ims/history?sessionId=...                   拿历史
 * - POST /ai/ims/memory/clear  Body: {sessionId}         清空某会话
 */
@RestController
@RequestMapping("/ai/ims")
public class ImsAskController {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final VectorStore longTermMemoryVectorStore;

    public ImsAskController(ChatClient.Builder chatClientBuilder,
                            ImsDeviceTool imsDeviceTool,
                            ChatMemory chatMemory,
                            @Qualifier("longTermMemoryVectorStore") VectorStore longTermMemoryVectorStore) {
        this.chatMemory = chatMemory;
        this.longTermMemoryVectorStore = longTermMemoryVectorStore;

        // 1. 构造 advisor（关键：指定 conversationId 来自 sessionId 参数）
        // Spring AI 1.0.0 的标准做法：MessageChatMemoryAdvisor.builder(memory) 即可，
        // conversationId 通过运行时 .advisors(a -> a.param(...)) 注入
        //
        // 【第一轨 = JDBC 精确记忆（PostgreSQL 持久化）】
        // chatMemory 底层已从 InMemory 切换到 JdbcChatMemoryRepository（见 ChatMemoryConfig），
        // 多轮对话原文存进 PG 的 SPRING_AI_CHAT_MEMORY 表，应用重启不丢、多实例可共享。
        // 不使用 Embedding / Ollama，零额外模型依赖。
        // 1. 记忆 Advisor 已全局化（advisor.AgentMemoryAdvisor.globalMemoryAdvisor Bean），
        //    所有 ChatClient 自动带，这里不再手动 new，避免与全局重复落库。

        // 2. 第二轨 = 长期向量记忆（双轨互补）
        // 在 LLM 调用前检索 PGVector 里语义相关的历史问答对，拼进 SystemMessage，
        // 让模型「想起」100 轮前聊过的内容。写入在拿到完整答案后（见 storeLongTermMemory）。
        VectorMemoryAdvisor vectorMemoryAdvisor = new VectorMemoryAdvisor(
                longTermMemoryVectorStore, 5, 0.6);

        // 3. ChatClient 默认带这两个 advisor（全局）
        this.chatClient = chatClientBuilder
                .defaultSystem("""
                        你是一个车间设备助手，负责回答用户关于 IMS 系统中设备的问题（设备名、状态、位置、数量统计等）。

                        工作流程：
                        1. 用户提问时，调用 queryDevices / countDevicesByState / listDevicesByState 工具
                        2. 每条设备记录会同时包含 `stateCn`（中文含义）和 `stateOriginal`（原始码）字段
                        3. **必须使用 `stateCn` 字段回答用户问题**
                        4. 统计数量时优先调用 countDevicesByState（不要自己遍历设备数）

                        多轮对话规则：
                        - 尊重上文，例如用户问"它最近一周报警"，"它"指上文的某台设备
                        - 如果上文没指明，**主动澄清**而不是瞎猜

                        硬约束：
                        - 禁止编造数字 / 百分比 / 估算
                        - 禁止调用除上述三个 IMS 工具外的其他工具
                        - 回答简短（3-6 句）
                        """)
                .defaultTools(imsDeviceTool)
                // 【安全 Advisor】企业级护栏：注入拦截(before) + 输出泄露检测(after)
                // 默认 blockMalicious=true，命中恶意提示注入直接换成拒绝话术
                .defaultAdvisors(vectorMemoryAdvisor, new SecurityAdvisor(true))
                .build();
    }

    @GetMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestParam("question") String question,
                                @RequestParam(value = "sessionId", required = false) String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "default-" + UUID.randomUUID();
        }
        return stream(question, sessionId);
    }

    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStreamPost(@RequestBody Map<String, String> request) {
        String question = request.getOrDefault("question", "");
        String sessionId = request.getOrDefault("sessionId", "");
        if (sessionId.isBlank()) sessionId = "default-" + UUID.randomUUID();
        return stream(question, sessionId);
    }

    /**
     * 公共流式方法：
     * 关键：.advisors(a -> a.param("chat_memory_conversation_id", sessionId))
     *   Spring AI 1.0.0 中 advisor 拿 conversationId 的 key 是字符串常量
     */
    private SseEmitter stream(String question, String sessionId) {
        long t0 = System.currentTimeMillis();
        System.out.println("[ImsAsk] ▶ /ask/stream start session=" + sessionId + " q=" + question);

        SseEmitter emitter = new SseEmitter(60_000L);
        // 累积流式回答，用于 onComplete 时写长期向量记忆（避免存半截）
        StringBuilder answerBuf = new StringBuilder();

        // 这 4 个监听器全注册 — 防 SSE 挂起
        emitter.onCompletion(() -> {
            long ms = System.currentTimeMillis() - t0;
            System.out.println("[ImsAsk] ✓ done session=" + sessionId + " 总耗时=" + ms + "ms");
        });
        emitter.onTimeout(() -> {
            System.out.println("[ImsAsk] ⚠ timeout, force complete");
            emitter.complete();
        });
        emitter.onError(e -> {
            System.out.println("[ImsAsk] ✗ sse error: " + e.getMessage());
            emitter.completeWithError(e);
        });

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                emitter.send(SseEmitter.event().name("start")
                        .data("{\"sessionId\":\"" + esc(sessionId) + "\",\"q\":\"" + esc(question) + "\"}"));

                long tLlm = System.currentTimeMillis();
                System.out.println("[ImsAsk] ▶ LLM.stream start");

                chatClient.prompt(question)
                        // 把 sessionId 注入 advisor context
                        // key 必须是 "chat_memory_conversation_id"（Spring AI 1.0.0 字符串常量）
                        .advisors(a -> a.param("chat_memory_conversation_id", sessionId))
                        .stream()
                        .content()
                        .doOnSubscribe(s -> System.out.println("[ImsAsk] ... LLM subscribed"))
                        .doOnComplete(() -> {
                            long ms = System.currentTimeMillis() - tLlm;
                            System.out.println("[ImsAsk] ✓ LLM.end 耗时=" + ms + "ms");
                            // 流式结束 → 把「问答对」写入长期向量记忆
                            storeLongTermMemory(sessionId, question, answerBuf.toString());
                            try { emitter.send(SseEmitter.event().name("done").data("[DONE]")); } catch (IOException ignore) {}
                            emitter.complete();
                        })
                        .doOnError(err -> {
                            long ms = System.currentTimeMillis() - tLlm;
                            System.out.println("[ImsAsk] ✗ LLM err 耗时=" + ms + "ms " + err.getMessage());
                            try { emitter.send(SseEmitter.event().name("error").data(err.getMessage())); } catch (IOException ignore) {}
                            emitter.complete();
                        })
                        .subscribe(chunk -> {
                            try {
                                answerBuf.append(chunk); // 累积完整答案
                                emitter.send(SseEmitter.event().name("token").data(chunk));
                            } catch (IOException ex) {
                                emitter.completeWithError(ex);
                            }
                        });

            } catch (Exception e) {
                System.out.println("[ImsAsk] ✗ 异常: " + e.getMessage());
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 非流式端点（给评测脚本和其他程序调用）
     * POST /ai/ims/ask  Body: {question, sessionId}
     */
    @PostMapping(value = "/ask", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> askNonStream(@RequestBody Map<String, String> req) {
        String question = req.getOrDefault("question", "");
        String sessionId = req.getOrDefault("sessionId", "");
        if (sessionId.isBlank()) sessionId = "default-" + UUID.randomUUID();

        System.out.println("[ImsAsk] ▶ /ask start session=" + sessionId + " q=" + question);

        // 同步调用（不流式）
        String finalSessionId = sessionId;
        String answer = chatClient.prompt(question)
                .advisors(a -> a.param("chat_memory_conversation_id", finalSessionId))
                .call()
                .content();

        // 输出脱敏（L6/LLM02 硬防线）：返回调用方前强制脱敏密钥/泄露，不依赖模型听话
        String safeAnswer = OutputSanitizer.sanitize(answer).cleaned;

        // 非流式：直接拿到完整答案（已脱敏）→ 写长期向量记忆
        storeLongTermMemory(finalSessionId, question, safeAnswer);

        System.out.println("[ImsAsk] ✓ /ask done session=" + sessionId);
        return Map.of("sessionId", sessionId, "question", question, "answer", safeAnswer);
    }

    @GetMapping("/history")
    public Map<String, Object> history(@RequestParam("sessionId") String sessionId) {
        return Map.of(
                "sessionId", sessionId,
                "messages", chatMemory.get(sessionId)
        );
    }

    @PostMapping("/memory/clear")
    public Map<String, Object> clear(@RequestBody Map<String, String> req) {
        String sessionId = req.get("sessionId");
        chatMemory.clear(sessionId);
        return Map.of("sessionId", sessionId, "cleared", true);
    }

    /**
     * 长期记忆检索调试端点（验证双轨互补用）
     * GET /ai/ims/longterm/search?q=3D1F设备维护周期
     * 返回与 q 语义相关的历史问答对 top-5
     */
    @GetMapping("/longterm/search")
    public Map<String, Object> longTermSearch(@RequestParam("q") String q) {
        List<Document> hits = longTermMemoryVectorStore.similaritySearch(
                SearchRequest.builder().query(q).topK(5).similarityThreshold(0.5).build());
        List<Map<String, Object>> list = hits.stream()
                .map(d -> Map.<String, Object>of(
                        "conversationId", d.getMetadata().getOrDefault("conversationId", ""),
                        "content", d.getText().replaceAll("<think>.*?</think>", "").trim()))
                .collect(Collectors.toList());
        return Map.of("query", q, "hits", list);
    }

    /**
     * 把「问答对」写入长期向量记忆（第二轨）
     * - 在拿到完整答案后调用（流式在 onComplete、非流式在 call 返回后）
     * - 过滤 AI 的 <think> 推理块，只存干净的 Q+A
     * - 存为「Q: ...\nA: ...」一个文档，语义单元完整（比逐条消息好）
     */
    private void storeLongTermMemory(String sessionId, String question, String answer) {
        if (answer == null || answer.isBlank()) {
            return;
        }
        String cleanAnswer = answer.replaceAll("<think>.*?</think>", "").trim();
        // 输出脱敏（L6）：写入长期向量记忆前强制脱敏，避免密钥/敏感信息被持久化进记忆库。
        // 记忆库后续会被 VectorMemoryAdvisor 重新注入对话，若不先清干净，会构成"间接提示注入"回环。

        cleanAnswer = OutputSanitizer.sanitize(cleanAnswer).cleaned;
        if (cleanAnswer.isBlank()) {
            return;
        }
        try {
            Document doc = new Document("Q: " + question + "\nA: " + cleanAnswer);
            doc.getMetadata().put("conversationId", sessionId);
            doc.getMetadata().put("type", "qa_pair");
            doc.getMetadata().put("ts", java.time.Instant.now().toString());
            longTermMemoryVectorStore.add(List.of(doc));
            System.out.println("[ImsAsk] ✓ 长期记忆写入 session=" + sessionId + " q=" + question);
        } catch (Exception e) {
            // 写记忆失败不能影响主流程（回答已经返回给用户了）
            System.out.println("[ImsAsk] ✗ 长期记忆写入失败 session=" + sessionId + " : " + e.getMessage());
        }
    }

    private static void send(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
