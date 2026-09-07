package com.springai.springai.memory.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 长期记忆向量库配置 —— 双轨互补的「第二轨」（向量语义检索）
 *
 * 【为什么单独一张表】
 * - 第一轨：JdbcChatMemoryRepository 存 SPRING_AI_CHAT_MEMORY（精确文本，管最近 10 条）
 * - 第二轨：本配置建 chat_memory 表（PGVector，管跨很远的语义召回）
 * - 两者都落在同一个本地 PG，但表独立、用途独立，互不污染
 * - RagController 用的是内存 SimpleVectorStore，也不冲突
 *
 * 【维度必须对齐】
 * dimensions(1536) 须与 MiniMax embo-01 输出维度、yml 里 spring.ai.vectorstore.pgvector.dimensions=1536 一致
 *
 * 【写入时机】
 * 不在这里写。本 Bean 只提供 VectorStore 能力，真正的「问答对写入」在
 * ImsAskController 拿到完整答案后调用 vectorStore.add(...)（流式在 onComplete 写）。
 */
@Configuration
public class VectorMemoryConfig {

    @Bean
    public VectorStore longTermMemoryVectorStore(JdbcTemplate jdbcTemplate,
                                                  EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("chat_memory")   // 独立表，不与 RAG/精确记忆混
                .dimensions(1536)                            // 对齐 MiniMax embo-01
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)                     // 启动自动建表 + 索引
                .build();
    }
}
