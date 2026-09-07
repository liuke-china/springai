package com.springai.springai.smalldemo.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class PgVectorStoreConfig {

    /**
     * PGVector 向量库 Bean，聊天记录存进 PostgreSQL 的 vector_store 表
     * 依赖 EmbeddingModel（本项目 bge-m3，1024 维）和 JdbcTemplate（spring.datasource）
     */
    @Bean
    public VectorStore pgVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("chat_memory")
                .dimensions(1536)   // 对齐 MiniMax embo-01（1536 维），yml 同值
                .initializeSchema(true)
                .build();
    }

    /**
     * RAG 专用 PGVector 向量库 Bean，知识库文档存进独立的 chat_document 表
     * 与聊天记忆的 chat_memory 表分开，避免 stats 把聊天记录也算进知识库
     * initializeSchema(true) 让 Spring 在 Bean 初始化时自动建表（首次运行建 chat_document）
     */
    @Bean
    public VectorStore chatRagVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("chat_document")
                .dimensions(1536)   // 对齐 MiniMax embo-01（1536 维）
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .build();
    }
}
