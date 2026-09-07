package com.springai.springai.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Query 增强器（企业 RAG 检索前处理）
 * =====================================================================
 * 负责两类增强，且都按「企业成本意识」做了条件触发，不是每次都调 LLM：
 *
 *  1) Query Rewrite（查询改写 / 多轮补全）
 *     —— 解决多轮对话里的指代消解："这个呢？" → 结合上文变成完整问题。
 *     —— 企业触发逻辑：仅当存在对话历史(history 非空)时才改写；
 *        首轮单问直接透传，省一次 LLM 调用（省延迟+成本）。
 *
 *  2) HyDE（Hypothetical Document Embeddings，假设文档嵌入）
 *     —— 让 LLM 先针对问题编一段「假设答案/假设文档」，用这段文档的向量去搜库。
 *     —— 为什么有效：用户问句是疑问句/短句，知识库是陈述长文，两者在向量空间有
 *        「分布偏移(distribution shift)」；假设文档是「文档体」，和真文档分布更近 → 召回更准。
 *     —— 企业触发逻辑：默认【条件触发】——向量路 top1 余弦距离高于阈值 hyde-threshold 时，
 *        才花一次 LLM 做 HyDE 兜底增强。避免每次都多调一次 LLM 带来的延迟与幻觉噪声。
 *        （也提供 always 模式供实验、off 模式关闭，通过 hyde-mode 配置。）
 */
@Component
public class QueryAugmenter {

    private static final Logger log = LoggerFactory.getLogger(QueryAugmenter.class);

    private final ChatClient chatClient;

    /** HyDE 触发阈值：向量路 top1 余弦距离高于此值（即召回弱）才启用 HyDE（默认 0.45） */
    @Value("${spring.rag.enterprise.hyde-threshold:0.45}")
    private double hydeThreshold;

    /** HyDE 模式：conditional（默认，按阈值触发）/ off（关闭，最快）/ always（每次都做，仅实验用） */
    @Value("${spring.rag.enterprise.hyde-mode:conditional}")
    private String hydeMode;

    /** Query Rewrite 开关：默认开启，但仅在有 history 时生效（单轮自动跳过） */
    @Value("${spring.rag.enterprise.rewrite-enabled:true}")
    private boolean rewriteEnabled;

    public QueryAugmenter(ChatClient.Builder chatClientBuilder) {
        // 单独构建一个「只做检索增强、不带生成人设」的客户端，避免污染阶段5的生成护栏
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 查询改写（多轮补全 / 指代消解）
     * @param question 用户原始问题
     * @param history 对话历史（之前的问题列表，旧→新）；为空表示单轮
     * @return 改写后的检索用 query（单轮且无 history 时原样返回，不调 LLM）
     */
    public String   rewrite(String question, List<String> history) {
        // 📌 企业默认：单轮首问不改写，直接透传，省一次 LLM 调用
        if (!rewriteEnabled || history == null || history.isEmpty()) {
            return question;
        }
        String context = String.join("\n", history);
        String rewritten = chatClient.prompt()
                .system("你是一个查询改写器。根据【对话历史】把【当前问题】改写成一个独立、完整、"
                        + "利于向量检索的问题。只输出改写后的问题本身，不要解释，不要加引号。"
                        + "【语言要求】输出语言必须为中文。")
                .user("【对话历史】\n" + context + "\n\n【当前问题】" + question + "\n\n【改写后问题】")
                .call()
                .content()
                .trim();
        log.info("[QueryAugmenter] 改写生效: {} -> {}", question, rewritten);
        return rewritten.isBlank() ? question : rewritten;
    }

    /**
     * 是否启用 HyDE（企业条件触发逻辑）
     * @param topVectorScore 向量路召回 top1 的余弦距离（0=最像，越大越不相关；COSINE_DISTANCE 下 PgVectorStore.getScore() 返回的就是距离）
     * @param searchQuery 改写后的检索 query（仅用于日志/扩展，当前判定只看分数）
     */
    public boolean shouldUseHyde(double topVectorScore, String searchQuery) {
        return switch (hydeMode.toLowerCase()) {
            case "off"    -> false;                          // 关闭 HyDE
            case "always" -> true;                          // 实验用：每次都做
            default       -> topVectorScore > hydeThreshold; // conditional：召回偏弱(距离大)才兜底增强
        };
    }

    /**
     * HyDE：让 LLM 针对问题生成一段「假设答案/假设文档」（文档体）。
     * 调用方应拿这段文本（而非原问题）去 vectorStore 检索，使其向量接近知识库文档分布。
     */
    public String hyde(String query) {
        String hypothesis = chatClient.prompt()
                .system("你是一个知识库检索助手。针对【问题】写一段可能出现在企业知识库中的参考文档片段"
                        + "（陈述体、包含具体条款/数字），用于辅助向量检索。只输出文档片段本身，不要解释。"
                        + "【语言要求】必须使用与【问题】完全相同的语言输出：问题为中文则返回内容为中文，"
                        + "问题为英文则返回内容为英文，绝对禁止翻译成其他语言或与问题不同的语言混用。")
                .user("【问题】" + query + "\n\n【假设文档片段】")
                .call()
                .content()
                .trim();
        log.info("[QueryAugmenter] HyDE 生成假设文档（{} 字）", hypothesis.length());
        // 兜底：生成失败/为空时用原 query，保证检索不中断
        return hypothesis.isBlank() ? query : hypothesis;
    }
}
