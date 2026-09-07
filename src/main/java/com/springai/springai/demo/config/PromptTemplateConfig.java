package com.springai.springai.demo.config;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Prompt 模板配置类
 *
 * 【推荐做法】
 * 将常用的 Prompt 模板定义为 @Bean
 * - 启动时加载，常驻内存
 * - 避免每次请求都 new 对象
 * - 集中管理，便于维护
 */
@Configuration
public class PromptTemplateConfig {

    /**
     * 翻译模板
     * 使用方式：在 Controller 中注入即可
     */
    @Bean
    public PromptTemplate translatorTemplate() {
        return new PromptTemplate(
                "你是一个专业翻译官，请把以下内容翻译成 {targetLanguage}，只输出翻译结果，不要解释：\n{text}"
        );
    }

    /**
     * 摘要生成模板
     */
    @Bean
    public PromptTemplate summarizerTemplate() {
        return new PromptTemplate(
                "请为以下内容生成一段 {length} 字的摘要：\n{content}"
        );
    }

    /**
     * 代码审查模板
     */
    @Bean
    public PromptTemplate codeReviewTemplate() {
        return new PromptTemplate(
                "你是一个资深代码审查员，请审查以下 {language} 代码：\n```{language}\n{code}\n```\n" +
                "请从以下维度评分（1-10分）并给出建议：\n1. 代码规范性\n2. 安全性\n3. 性能\n4. 可读性"
        );
    }

    /**
     * 邮件生成模板
     */
    @Bean
    public PromptTemplate emailTemplate() {
        return new PromptTemplate(
                "帮写一封邮件：\n收件人：{to}\n主题：{subject}\n内容要点：{points}\n要求：格式规范、语言专业、语气{tone}"
        );
    }

    // ==================== 企业常用模板 ====================

    /**
     * SQL 生成模板
     */
    @Bean
    public PromptTemplate sqlGeneratorTemplate() {
        return new PromptTemplate("""
            你是数据库专家。

            ## 数据库类型
            {dbType}

            ## 表结构
            {tableSchema}

            ## 需求
            {requirement}

            请生成对应的 SQL 语句，只输出 SQL，不要解释。
            """);
    }

    /**
     * 代码解释模板
     */
    @Bean
    public PromptTemplate codeExplainerTemplate() {
        return new PromptTemplate("""
            请解释以下 {language} 代码的功能：

            ```{language}
            {code}
            ```

            要求：
            1. 说明代码整体功能
            2. 解释关键逻辑
            3. 用通俗语言解释
            """);
    }

    /**
     * 数据分析报告模板
     */
    @Bean
    public PromptTemplate dataAnalysisTemplate() {
        return new PromptTemplate("""
            你是数据分析师。

            ## 数据
            {data}

            ## 分析维度
            {dimensions}

            ## 要求
            1. 生成 {length} 字的报告
            2. 包含关键发现
            3. 给出建议

            请按以下格式输出：
            ### 一、概述
            ### 二、关键发现
            ### 三、建议
            """);
    }

    /**
     * 客服自动回复模板
     */
    @Bean
    public PromptTemplate customerServiceTemplate() {
        return new PromptTemplate("""
            你是 {company} 的客服助手，名字叫 {botName}。

            ## 产品信息
            {productInfo}

            ## 客服规范
            1. 态度友好、专业
            2. 不能回答不知道的问题
            3. 如需转人工，及时提示

            ## 用户问题
            {question}

            ## 上下文
            {context}
            """);
    }

    /**
     * 内容审核模板
     */
    @Bean
    public PromptTemplate contentModerationTemplate() {
        return new PromptTemplate("""
            请审核以下内容，判断是否违规：

            内容：{content}

            审核维度：
            1. 违规违法
            2. 低俗色情
            3. 暴力恐怖
            4. 广告营销
            5. 政治敏感

            输出格式（JSON）：
            \\{
              "passed": true/false,
              "reason": "具体原因，哪些字有问题",
              "riskLevel": "低/中/高"
            \\}
            """);
    }

    /**
     * 文本分类模板
     */
    @Bean
    public PromptTemplate textClassifierTemplate() {
        return new PromptTemplate("""
            请将以下文本分类到指定类别中：

            文本：{content}

            候选类别：{categories}

            要求：
            1. 只选择一个最合适的类别
            2. 输出类别名称
            3. 给出分类理由
            """);
    }

    /**
     * JSON 结构化输出模板
     */
    @Bean
    public PromptTemplate jsonOutputTemplate() {
        return new PromptTemplate("""
            请从以下文本中提取信息，输出 JSON：

            文本：{content}

            JSON 格式：
            \\{
              "name": "姓名",
              "age": "年龄",
              "phone": "电话",
              "email": "邮箱"
            \\}

            只输出 JSON，不要其他内容。
            """);
    }
}
