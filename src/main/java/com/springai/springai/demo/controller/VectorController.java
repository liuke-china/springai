package com.springai.springai.demo.controller;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量数据库操作控制器
 *
 * 【定位】VectorStore 的底层原子操作（增/查/删），不含 AI 问答——
 *       RagController 是"检索+回答"，本类只管"文档怎么进向量库、怎么查出来"
 *
 * 【核心概念】
 * 每条文本 → 嵌入模型转成 1024 维向量 → 存入 PGVector（pgvector 扩展）
 * 搜索时把查询文本也转向量，按余弦距离找最近的上 topK 条
 *
 * 【Postman 对应】
 * POSTMAN：demo.postman_collection.json
 * 分组：「5. Vector」→ add / batch-add / search 共 3 个接口
 * （delete/{id} 本类有实现，Postman 集合里没有对应条目）
 */
@RestController
@RequestMapping("/vector")
public class VectorController {

    private final VectorStore vectorStore;

    public VectorController(@Qualifier("chatRagVectorStore") VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    // ==================== 1. 添加单条文档 ====================

    /**
     * 演示：单条文本入库，体会"文本 → 向量 → PGVector"的最小单元
     * Postman：分组「5. Vector」→ add
     * 示例请求：POST /vector/add
     *          Body: {"content":"这是要存储的文本内容"}
     *
     * 流程：content + metadata（source/time）→ 构造 Document → vectorStore.add 自动向量化落库
     * 用途：测完立即用 search 搜相似内容，验证向量检索通不通
     */
    @PostMapping("/add")
    public Map<String, Object> add(@RequestBody Map<String, String> request) {
        String content = request.get("content");

        // 创建文档对象，metadata 可以存额外信息
        Document document = new Document(
            content,
            Map.of("source", "api", "time", String.valueOf(System.currentTimeMillis()))
        );

        // 自动调用嵌入模型生成向量，并存入 PGVector
        vectorStore.add(List.of(document));

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "文档添加成功");
        result.put("id", document.getId());
        return result;
    }

    // ==================== 2. 批量添加文档 ====================

    /**
     * 演示：一次入库多条文本（真实企业场景是批量灌文档）
     * Postman：分组「5. Vector」→ batch-add
     * 示例请求：POST /vector/batch-add
     *          Body: {"contents":["文本1","文本2"]}
     *
     * 流程：contents 数组 → 逐条构造 Document（metadata.index 记录原始顺序）→ 一次 add 批量入库
     * 注意：批量入库比循环调 add 少 N-1 次向量库往返，企业灌库都用批量
     */
    @PostMapping("/batch-add")
    public Map<String, Object> batchAdd(@RequestBody Map<String, List<String>> request) {
        List<String> contents = request.get("contents");

        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            documents.add(new Document(
                contents.get(i),
                Map.of("source", "batch-api", "index", String.valueOf(i))
            ));
        }

        vectorStore.add(documents);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "批量添加成功");
        result.put("count", documents.size());
        return result;
    }

    // ==================== 3. 相似度搜索 ====================

    /**
     * 演示：向量检索——RAG 第 4 阶段（Retrieval）的原始形态
     * Postman：分组「5. Vector」→ search
     * 示例请求：GET /vector/search?query=我喜欢什么&topK=3
     *
     * 流程：query 转向量 → PGVector 按余弦距离找最近 topK 条 → 返回 id/content/metadata/score
     * 关键点：score 取的是 metadata.distance（距离越小越相似）
     * 场景：add/batch-add 之后立刻调这个，能搜到刚存的内容说明链路通
     */
    @GetMapping("/search")
    public List<Map<String, Object>> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "3") int topK) {

        // 构建搜索请求：把 query 转成向量，找最相似的 topK 个文档
        SearchRequest request = SearchRequest.builder()
            .query(query)
            .topK(topK)
            .build();

        List<Document> documents = vectorStore.similaritySearch(request);

        // 封装返回结果
        return documents.stream().map(doc -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", doc.getId());
            item.put("content", doc.getText());
            item.put("metadata", doc.getMetadata());
            item.put("score", doc.getMetadata().get("distance"));
            return item;
        }).toList();
    }

    // ==================== 4. 删除文档 ====================

    /**
     * 演示：按 id 删除向量库文档
     * 示例请求：DELETE /vector/delete/{id}
     *          （id 用 add 接口返回的 document id）
     *
     * 注意：Postman 集合「5. Vector」分组没有这个条目，需在 Postman 里手动新建 DELETE 请求测
     */
    @DeleteMapping("/delete/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        vectorStore.delete(List.of(id));

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "文档删除成功");
        result.put("id", id);
        return result;
    }


}
