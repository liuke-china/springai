package com.springai.springai.memory.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * ChatMemory 多轮对话配置 —— JDBC 精确记忆（PostgreSQL 持久化版）
 *
 * 【技术选型：为什么从 InMemory 换成 JDBC】
 * - 旧版：InMemoryChatMemoryRepository —— 对话存在 JVM 内存，应用重启即丢，且多实例不共享
 * - 新版：JdbcChatMemoryRepository —— 对话原文存进 PostgreSQL（spring.datasource 已配），
 *         重启不丢、多实例共享、可审计（DBA 能直接查历史）
 *
 * 【本配置怎么做】
 * - JdbcChatMemoryRepository 的 Bean 由 Spring AI 自动配置创建（前提是引入了
 *   spring-ai-starter-model-chat-memory-repository-jdbc 依赖，且 yml 配了
 *   spring.ai.chat.memory.repository.jdbc.initialize-schema=always 自动建表）。
 *   dialect（方言）从 JDBC URL 自动检测为 PostgreSQL，无需手写。
 * - 本类只负责定义 ChatMemory（对话窗口）Bean，引用上面自动配置的 repository，
 *   maxMessages(10) 控制滑动窗口只保留最近 10 条，防止历史无限膨胀拖慢推理。
 * - 用 @Primary 确保注入时优先用本窗口配置（覆盖 Spring AI 默认窗口大小）。
 *
 * 【精确记忆 vs 向量语义记忆】
 * - 精确记忆（本方案）：原样存每条消息，按 conversationId 读取最近 N 条。
 *   优点：零 Embedding 依赖、不依赖 Ollama、便宜、可控。企业 90% 场景用这种。
 * - 向量语义记忆：消息向量化存 PGVector，按语义检索。需要 Embedding 模型
 *   （本项目原用 Ollama 本地 Embedding，已弃用，故不采用）。
 *
 * 【⚠️ 已知限制】
 * JdbcChatMemoryRepository 会静默过滤 tool call 消息（AssistantMessage 含 tool calls、
 * ToolResponseMessage 不入库）。也就是说，若开启了 @Tool 工具调用（如 ImsDeviceTool），
 * 工具调用的中间消息不会持久化进记忆——多轮对话里"工具上下文"会丢。
 * 若要完整持久化（含工具消息），需用 Spring AI Session 项目 + JDBC Session 存储。
 * 当前 IMS 场景影响有限：用户多轮通常问"设备/区域"等自然语言，工具结果已体现在最终回复里。
 */
@Configuration
public class ChatMemoryConfig {

    /**
     * 对话窗口记忆 Bean（引用自动配置的 JDBC Repository）
     *
     * @param chatMemoryRepository Spring AI 自动配置注入的 JdbcChatMemoryRepository
     * @return 滑动窗口记忆，保留最近 10 条
     */
    @Bean
    @Primary
    public ChatMemory chatMemory(JdbcChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(10)
                .build();
    }
}
