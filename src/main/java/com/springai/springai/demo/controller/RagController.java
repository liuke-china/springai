package com.springai.springai.smalldemo.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * RAG（检索增强生成）控制器 - 简化版
 * 【什么是 RAG】
 * RAG = Retrieval Augmented Generation（检索增强生成）
 * 核心：先检索相关文档，再让 AI 基于文档回答，避免 AI 瞎编
 *
 * 【工作流程】
 * 1. 文档上传 → 分割成小块 → 向量化 → 存入向量库
 * 2. 用户提问 → 向量化 → 检索相似文档 → AI 基于文档回答
 */
@RestController
@RequestMapping("/ai/rag")
public class RagController {

    private final ChatClient chatClient;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;

    @Value("${rag.document.path:./documents}")
    private String documentPath;

    public RagController(ChatClient.Builder chatClientBuilder,
                         EmbeddingModel embeddingModel,
                         @Qualifier("chatRagVectorStore") VectorStore vectorStore) {
        this.chatClient = chatClientBuilder.build();
        this.embeddingModel = embeddingModel;
        // 知识库存进 PostgreSQL 的 chat_document 表（PgVectorStore Bean，重启不丢）
        this.vectorStore = vectorStore;

        // 创建文档目录
        try {
            Files.createDirectories(Path.of(documentPath));
        } catch (Exception e) {
            // 忽略
        }
    }


    // ==================== 1. 初始化演示知识库 ====================
    /*
     * 测试：POST /ai/rag/init-demo盘
     */
    @PostMapping("/init-demo")
    public Map<String, Object> initDemo() {
        Map<String, Object> result = new HashMap<>();

        try {
            List<String> texts = List.of(
                    "公司年假制度：入职满1年不满10年的员工，年休假5天；满10年不满20年的，年休假10天；满20年的，年休假15天。",
                    "加班申请流程：员工需提前填写加班申请单，经直属上级审批后提交人力资源部备案。",
                    "病假规定：请病假需当天9点前通过OA系统提交，并上传医院诊断证明。",
                    "我们的企业管理系统（IMS）功能包括：员工管理、考勤管理、薪资管理、审批流程。",
                    "IMS技术支持：邮箱 support@company.com，电话 400-xxx-xxxx，工作时间周一至周五 9:00-18:00。",
                    "办公时间：周一至周五 9:00-12:00，13:00-18:00。迟到超过30分钟记旷工半天。"
            );

            int totalChunks = 0;
            for (String text : texts) {
                List<Document> docs = splitText(text, 500, "demo", "init");
                vectorStore.add(docs);
                totalChunks += docs.size();
            }

            result.put("success", true);
            result.put("message", "演示知识库初始化成功");
            result.put("totalChunks", totalChunks);

        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "初始化失败：" + e.getMessage());
        }

        return result;
    }

    // ==================== 2. 上传文档到知识库 ====================
    /*
     * 测试：POST /ai/rag/upload
     * Form-Data: file=xxx.txt, knowledgeBase=company
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "knowledgeBase", defaultValue = "default") String knowledgeBase) {

        Map<String, Object> result = new HashMap<>();

        try {
            // 1. 校验文件
            String filename = file.getOriginalFilename();
            if (filename == null || !filename.endsWith(".txt")) {
                result.put("success", false);
                result.put("message", "只支持 .txt 文件");
                return result;
            }

            // 2. 读取内容
            String content = new String(file.getBytes());

            // 3. 分割文档（每500字符一块）
            List<Document> documents = splitText(content, 500, knowledgeBase, filename);

            // 4. 存入向量库
            vectorStore.add(documents);

            result.put("success", true);
            result.put("message", "文档上传成功");
            result.put("chunks", documents.size());

        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "上传失败：" + e.getMessage());
        }

        return result;
    }

    // ==================== 3. 添加纯文本到知识库 ====================
    /*
     * <p>
     * 测试：POST /ai/rag/add-text
     * Body: {"text":"公司规定：年假5天","knowledgeBase":"hr"}
     */
    @PostMapping("/add-text")
    public Map<String, Object> addText(@RequestBody Map<String, String> request) {
        Map<String, Object> result = new HashMap<>();

        try {
            String text = request.get("text");
            String knowledgeBase = request.getOrDefault("knowledgeBase", "default");

            if (text == null || text.isEmpty()) {
                result.put("success", false);
                result.put("message", "文本不能为空");
                return result;
            }

            List<Document> documents = splitText(text, 500, knowledgeBase, "manual");
            vectorStore.add(documents);

            result.put("success", true);
            result.put("message", "文本已添加");
            result.put("chunks", documents.size());

        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "添加失败：" + e.getMessage());
        }

        return result;
    }

    /**
     * RAG 问答
     * <p>
     * 测试：POST /ai/rag/query
     * Body: {"question":"年假多少天？"}
     */
    @PostMapping("/query")
    public Map<String, Object> query(@RequestBody Map<String, String> request) {
        Map<String, Object> result = new HashMap<>();

        try {
            String question = request.get("question");
            if (question == null || question.isEmpty()) {
                result.put("success", false);
                result.put("message", "问题不能为空");
                return result;
            }

            // 1. 检索相关文档（取前3个）
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(question)
                    .topK(3)
                    .build();

            List<Document> documents = vectorStore.similaritySearch(searchRequest);

            if (documents.isEmpty()) {
                result.put("success", true);
                result.put("answer", "知识库中没有找到相关内容。");
                return result;
            }

            // 2. 构建上下文
            StringBuilder context = new StringBuilder();
            for (int i = 0; i < documents.size(); i++) {
                context.append(String.format("【文档%d】%s\n\n", i + 1, documents.get(i).getText()));
            }

            // 3. 构建 RAG Prompt
            String prompt = String.format("""
                    你是公司智能助手，请基于以下参考文档回答用户问题。
                    
                    【参考文档】
                    %s
                    
                    【用户问题】%s
                    
                    回答要求：
                    1. 如果参考文档包含相关信息，请直接回答
                    2. 如果参考文档确实完全不包含相关信息，请回答"文档中没有相关信息"
                    3. 回答要简洁明了
                    
                    【回答】
                    """, context, question);

            // 4. 调用 AI
            String answer = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            result.put("success", true);
            result.put("question", question);
            result.put("answer", answer);
            result.put("retrievedDocs", documents.size());

        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "查询失败：" + e.getMessage());
        }

        return result;
    }

    /**
     * 文本分割工具方法
     */
    private List<Document> splitText(String text, int chunkSize, String knowledgeBase, String source) {
        List<Document> documents = new ArrayList<>();

        // 简单分割：按 chunkSize 字符分割
        for (int i = 0; i < text.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, text.length());
            String chunk = text.substring(i, end);

            Document doc = new Document(chunk);
            doc.getMetadata().put("knowledgeBase", knowledgeBase);
            doc.getMetadata().put("source", source);
            doc.getMetadata().put("chunkIndex", documents.size());

            documents.add(doc);
        }

        return documents;
    }
}
