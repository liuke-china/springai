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
 * 演示文档的添加、搜索、删除
 */
@RestController
@RequestMapping("/vector")
public class VectorController {

    private final VectorStore vectorStore;

    public VectorController(@Qualifier("chatRagVectorStore") VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 添加文档到向量库
     * POST /vector/add
     * Body: {"content":"这是要存储的文本内容"}
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

    /**
     * 批量添加文档到向量库
     * POST /vector/batch-add
     * Body: {"contents":["文本1","文本2",...]}
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

    /**
     * 相似度搜索
     * GET /vector/search?query=关键词&topK=3
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

    /**
     * 删除文档
     * DELETE /vector/delete/{id}
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
