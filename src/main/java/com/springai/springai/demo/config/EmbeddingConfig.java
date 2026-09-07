package com.springai.springai.smalldemo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Embedding 模型配置 —— RAG / 长期向量记忆核心。
 *
 * 2026-09-02 切换：SiliconFlow bge-m3（1024 维）→ MiniMax embo-01（1536 维）。
 * 原因：SiliconFlow 账户余额耗尽（402），而项目已有可用的 MiniMax API Key
 * （聊天与 embedding 共用同一 key），故 embedding 一并切到 MiniMax，整个项目只用 MiniMax 一家。
 *
 * MiniMax embedding 注意点（与 OpenAI 不兼容，故用自写 MiniMaxEmbeddingModel 适配器）：
 * 1. 请求体字段是 texts（数组）而非 input；
 * 2. 必传 type=db|query（非对称检索：入库用 db，查询用 query）；
 * 3. 响应字段是 vectors（数组的数组）而非 OpenAI 的 data[].embedding；
 * 4. 固定 1536 维 → spring.ai.vectorstore.pgvector.dimensions 必须同步改成 1536，
 *    且旧 1024 维 PGVector 表须 DROP 后由 initialize-schema 重建（维度在建表时固化，无法 ALTER）。
 *
 * API Key：复用 spring.ai.openai.api-key（即 MiniMax API Key）。
 */
@Configuration
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    /** MiniMax OpenAI 兼容网关根地址；MiniMaxEmbeddingModel 在其后拼 /embeddings → api.minimax.chat/v1/embeddings */
    private static final String EMBEDDING_BASE_URL = "https://api.minimax.chat/v1";

    /** MiniMax  embedding 模型 ID（固定 1536 维） */
    private static final String EMBEDDING_MODEL = "embo-01";

    /** PGVector 维度，须与 MiniMax embo-01 输出维度一致 */
    private static final int EMBEDDING_DIMENSIONS = 1536;

    // 复用 MiniMax 聊天模型的 key（spring.ai.openai.api-key）
    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Bean
    @Primary
    public EmbeddingModel embeddingModel() {
        // 启动即 fail-fast：key 没配会直接在这里抛异常，而不是运行时 cryptic 402/404
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "MiniMax embedding 需要 api-key：请确认 spring.ai.openai.api-key 已配置 MiniMax API Key，"
                    + "否则 embedding（RAG / 长期向量记忆）无法工作。");
        }

        MiniMaxEmbeddingModel model = new MiniMaxEmbeddingModel(
                apiKey, EMBEDDING_MODEL, EMBEDDING_BASE_URL, EMBEDDING_DIMENSIONS);

        log.info("[EmbeddingConfig] MiniMax embedding 就绪：baseUrl={}, model={}, dimensions={}",
                EMBEDDING_BASE_URL, EMBEDDING_MODEL, EMBEDDING_DIMENSIONS);
        return model;
    }
}
