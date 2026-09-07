package com.springai.springai.smalldemo.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * MiniMax Embedding 适配器（替代 SiliconFlow bge-m3）。
 *
 * 为什么不直接用 Spring AI 的 OpenAiEmbeddingModel：
 * MiniMax 的 /v1/embeddings 是非对称检索接口，要求必传 type=db|query，
 * 且请求体字段是 texts（数组）而非 OpenAI 的 input。Spring AI 原生 OpenAiEmbeddingModel
 * 不会发 type 字段、发的是 input → 直连会被 400 拒绝。故这里手写 RestClient 适配器。
 *
 * 维度：MiniMax embo-01 固定 1536 维（≠ 原 bge-m3 的 1024），
 * 因此切换后 PGVector 表须以 dimensions=1536 重建（DROP 旧表 + initialize-schema）。
 *
 * type 映射（非对称检索关键）：
 *   - 单条查询（getQueryEmbedding）→ type=query
 *   - 批量文档入库（doAdd）→ type=db
 *   依据：PgVectorStore 对查询走 embed(String)（单条），对存储走 embed(List<Document>)/embed(Document)（批量/单文档）。
 */
public class MiniMaxEmbeddingModel implements EmbeddingModel {

    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final RestClient restClient;

    public MiniMaxEmbeddingModel(String apiKey, String model, String baseUrl, int dimensions) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("MiniMax embedding 需要 api-key（复用 spring.ai.openai.api-key 的 MiniMax API Key）");
        }
        this.apiKey = apiKey;
        this.model = (model != null && !model.isBlank()) ? model : "embo-01";
        this.dimensions = dimensions;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public float[] embed(String text) {
        return embedTexts(List.of(text), "query").get(0);
    }

    @Override
    public float[] embed(Document document) {
        return embedTexts(List.of(document.getText()), "db").get(0);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return embedTexts(texts, "db");
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions() != null ? request.getInstructions() : List.of();
        List<float[]> vectors = embedTexts(texts, "db");
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < vectors.size(); i++) {
            embeddings.add(new Embedding(vectors.get(i), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    private List<float[]> embedTexts(List<String> texts, String type) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        Map<String, Object> body = Map.of(
                "model", model,
                "texts", texts,
                "type", type
        );
        MiniMaxResp resp;
        try {
            resp = restClient.post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(MiniMaxResp.class);
        } catch (HttpStatusCodeException e) {
            throw new RuntimeException("MiniMax embedding 调用失败: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
        if (resp == null || resp.vectors == null) {
            throw new RuntimeException("MiniMax embedding 返回为空（检查账户额度 / type 字段）");
        }
        if (resp.baseResp != null && resp.baseResp.statusCode != 0) {
            throw new RuntimeException("MiniMax embedding 业务错误: " + resp.baseResp.statusCode + " " + resp.baseResp.statusMsg);
        }
        List<float[]> result = new ArrayList<>();
        for (List<Double> vec : resp.vectors) {
            float[] f = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) {
                f[i] = vec.get(i).floatValue();
            }
            result.add(f);
        }
        return result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class MiniMaxResp {
        public List<List<Double>> vectors;
        public MiniMaxBaseResp baseResp;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class MiniMaxBaseResp {
        public int statusCode;
        public String statusMsg;
    }
}
