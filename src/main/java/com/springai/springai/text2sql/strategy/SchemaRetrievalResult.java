package com.springai.springai.text2sql.strategy;

import java.util.List;

/**
 * 表结构召回策略（方案① Schema RAG）的产物。
 * 把"向量召回相关表 + 读取 DDL"这一步独立成对象，便于在 trace 里观察"召回了哪些表、是否回退全表"。
 */
public record SchemaRetrievalResult(
        List<String> tables,   // 向量召回命中的相关表名（按相似度排序）
        String schemaText,     // 实际注入 Prompt 的表结构(DDL)文本
        boolean fallback       // 是否因向量库无命中而回退到"全部表"
) {
}
