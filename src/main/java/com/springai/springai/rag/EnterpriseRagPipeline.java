package com.springai.springai.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.metadata.Usage;
import com.springai.springai.metrics.RagMetrics;

/**
 * 企业级 RAG 五阶段链路（生产可用骨架）
 * =====================================================================
 * 把 RAG 拆成 5 个独立、可单测的阶段方法，每个阶段都对应企业落地时的一个工程关注点：
 *
 *   阶段1 分块 Chunking   —— 递归字符分割 + 滑动窗口重叠，保证语义完整、跨块不丢上下文
 *   阶段2 向量化 Embedding —— 用 bge-m3 把文本变成 1024 维向量（本类显式演示一次，正常由存储阶段内部调用）
 *   阶段3 存储 Storage    —— 持久化进 PGVector 独立表 chat_document，重启不丢、可多实例共享
 *   阶段4 检索 Retrieval  —— 语义召回 + metadata(kb) 过滤 + 相似度阈值 + 真实 Cross-Encoder 重排(rerank)
 *   阶段5 生成 Generation —— 带引用的「接地」回答：只允许基于上下文，找不到就明说，杜绝瞎编
 *
 * 与 smalldemo 里 RagController 简化版的区别（也就是面试能讲的企业化点）：
 *   ✅ 持久化 PGVector（不是重启即丢的内存 SimpleVectorStore）
 *   ✅ 分块带 overlap（不是 300 字硬切无重叠）
 *   ✅ 检索带 metadata 过滤（按知识库隔离，避免跨库串味）
 *   ✅ 检索带 rerank（不再是「召回 top3 直接用」）
 *   ✅ 生成带引用 + 接地护栏（不是「给上下文就完事」）
 *
 * 依赖：复用项目已有的 EmbeddingConfig(bge-m3) 与本地 PG(5432/postgres, 123456)
 * 表：chat_document（与 vector_store / chat_memory 三表独立，互不污染）
 */
@RestController
@CrossOrigin(origins = "*")   // 放开跨域：允许 file:// 前端测试页直接调本机后端（仅测试控制器，范围可控）
@RequestMapping("/ai/rag/enterprise")
public class EnterpriseRagPipeline {

    private static final Logger log = LoggerFactory.getLogger(EnterpriseRagPipeline.class);

    private final ChatClient chatClient;
    private final ChatClient.Builder chatClientBuilder;
    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private PgVectorStore vectorStore;   // 本类自管的 PGVector 实例（独立表，@PostConstruct 中完成建表）
    private final Bm25Retriever bm25 = new Bm25Retriever();   // 📌 混合检索第二路：BM25 字面索引（内存，重启从 PG 恢复）
    private final QueryAugmenter queryAugmenter;   // 📌 检索前增强：query 改写 + HyDE（企业条件触发，非每次都调 LLM）
    private final ChatMemory chatMemory;           // 📌 多轮历史：复用项目 JDBC 记忆表（SPRING_AI_CHAT_MEMORY），前端只传 sessionId
    private final RagMetrics ragMetrics;           // 📌 可观测性：Micrometer 业务指标埋点（QPS/P99/Token用量/检索耗时）

    /** HNSW 搜索候选列表大小（ef_search）：调大召回率(context_recall)升但延迟(latency)涨（索引调优旋钮，默认 100） */
    @Value("${spring.rag.enterprise.ef-search:100}")
    private int efSearch;

    /** 检索事务模板：在同一 DB 连接/事务里执行 SET LOCAL hnsw.ef_search + 向量召回 */
    private TransactionTemplate transactionTemplate;

    /** 单块目标字符数（中文场景 500 字，配合 150 字 overlap 让边界知识点进两块，降召回漏失） */
    @Value("${spring.rag.enterprise.chunk-size:500}")
    private int chunkSize;

    /** 相邻块重叠字符数（150 字 = chunkSize 一半）：让边界知识点同时出现在相邻两块，检索不因切断而丢失 */
    @Value("${spring.rag.enterprise.overlap:150}")
    private int overlap;

    /**
     * 最终返回给 LLM 的文档数（rerank 后取前 N）。
     * 🟢 默认 = 6（v0 基线；RAGAS 实测 context_recall=0.577 为四轮最高，差异在裁判方差内）。
     * 🟡 [S3 现已 yml 开关化] scheme3-enabled=true 时 effectiveTopK() 返回 10（扩检索窗口 → 单 query
     *    总文字量 6×chunk → 10×chunk，预期覆盖更多参考事实点 → context_recall 升）。历史实测（300/150）反而：
     *    faithfulness 0.516 → 0.403 ↓0.118；context_recall 0.577 → 0.415 ↓0.162，净负面
     *    （更多块被 judge 当噪音/可编造素材 → F 更低；CR 也掉）。500/150 下是否逆转待本次 RAGAS 验证。
     */
    @Value("${rag.enterprise.topk:6}")
    private int topK;

    /**
     * S1/S2/S3 实验开关（yml / 命令行可配，默认全关 = v0 基线）。
     *  - scheme1-enabled: S1 = 阶段5 强约束 prompt + temperature 0.1（曾 300/150 实测净负面，故默认关）
     *  - scheme2-enabled: S2 = 生成后 faithfulness 护栏（事实核查员删无依据句，含删空兜底回退原答案）
     *  - scheme3-enabled: S3 = 最终 topK 6→10（曾 300/150 实测净负面，故默认关）
     */
    @Value("${rag.enterprise.scheme1-enabled:false}")
    private boolean scheme1Enabled;
    @Value("${rag.enterprise.scheme2-enabled:false}")
    private boolean scheme2Enabled;
    @Value("${rag.enterprise.scheme3-enabled:false}")
    private boolean scheme3Enabled;

    /** S2 事实核查员客户端（始终构建，仅 scheme2 开启时调用，避免二次判定污染主生成客户端） */
    private final ChatClient gateClient;

    /** 相似度下限：低于此分的召回直接丢弃，过滤噪声（bge-m3 余弦距离，0.4 为经验起点） */
    @Value("${rag.enterprise.similarity-threshold:0.4}")
    private double similarityThreshold;

    /**
     * 📌 P0 修复：rerank 后「动态 topK」截断阈值（解决 context_precision 偏低）
     * ===================================================================
     * 问题：原实现 rerank 后【只按数量】硬取满 topK=6 块，不看相关性分数。
     *       简单问题语料里可能只有 1-2 块真相关，硬凑 6 块 → 4-5 块是噪声
     *       → context_precision（上下文精确度）天然被拉到 0.33 左右。
     *       类比 SQL：原来是 ORDER BY score DESC LIMIT 6，缺了 WHERE score > 阈值。
     *
     * 修复：rerank 打完分后，按相关性分数截断，实际返回 1~topK 块可变（"够了就停"）。
     *
     * ⚠️ 为什么不是简单把 topK 6 改成 3？
     *    那叫 metric gaming（指标作弊）：无差别砍块会让复杂问题丢失必要上下文，
     *    context_precision 升但 context_recall（上下文召回率）掉，是拆东墙补西墙。
     *    动态截断只砍「分数不达标的」，简单问题砍得多、复杂问题砍得少，两个指标可同时守住。
     *
     * 双阈值设计（缺一不可）：
     *   - 绝对阈值 rerank-score-threshold：过滤客观弱相关块。
     *   - 相对阈值 rerank-relative-ratio：保留 ≥ top1 分数 × ratio 的块。
     *     必要性 → 不同 reranker 分数尺度不同（有的输出 logits、有的输出 sigmoid 归一化值），
     *     只用绝对阈值换个模型就失效；相对阈值让策略可移植。
     *   - 实际截断线 = max(绝对阈值, top1 × 相对比例)，两者取严。
     *   - 保底 rerank-min-docs：至少留 N 块，防止阈值设过高导致检索全空、答案变"资料未覆盖"。
     */
    @Value("${rag.enterprise.rerank-score-threshold:0.30}")
    private double rerankScoreThreshold;

    /** 相对阈值：保留 relevance_score ≥ top1 × 该比例 的块（跨 reranker 可移植） */
    @Value("${rag.enterprise.rerank-relative-ratio:0.35}")
    private double rerankRelativeRatio;

    /** 保底块数：无论阈值多严，至少保留这么多块，防止检索全空 */
    @Value("${rag.enterprise.rerank-min-docs:1}")
    private int rerankMinDocs;

    // 📌 C 方案 reranker：SiliconFlow cross-encoder 配置（key 复用 EmbeddingConfig 的兜底注入）
    @Value("${SILICONFLOW_API_KEY:${siliconflow.api-key:}}")
    private String siliconflowApiKey;
    private static final String RERANK_URL = "https://api.siliconflow.cn/v1/rerank";
    private static final String RERANK_MODEL = "BAAI/bge-reranker-v2-m3";
    private final ObjectMapper rerankObjectMapper = new ObjectMapper();

    public EnterpriseRagPipeline(ChatClient.Builder chatClientBuilder,
                                 JdbcTemplate jdbcTemplate,
                                 EmbeddingModel embeddingModel,
                                 QueryAugmenter queryAugmenter,
                                 ChatMemory chatMemory,
                                 RagMetrics ragMetrics) {
        this.chatClientBuilder = chatClientBuilder;
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.queryAugmenter = queryAugmenter;
        this.chatMemory = chatMemory;
        this.ragMetrics = ragMetrics;

        // =====================================================================
        // 生成客户端（阶段5）：S1 开关 = v0 朴素 prompt（默认） vs S1 强约束 prompt + temperature 0.1
        // =====================================================================
        String v0Prompt = """
                你是企业知识库智能助手。回答用户问题时，必须严格遵循以下规则：
                1. 只能依据【参考文档】里的内容作答，禁止编造任何文档之外的数字、政策、人名。
                2. 回答中凡引用到某条文档，必须在该句末尾标注出处，如「（见【文档2】）」。
                3. 若【参考文档】完全未覆盖用户问题，必须明确回答「知识库中未找到相关信息」，不要硬凑。
                4. 回答简洁、用中文、分点清晰。
                """;
        String s1Prompt = """
                你是企业知识库智能助手。你的唯一知识来源是下面提供的【参考文档】。必须严格遵循：
                1. 禁止编造：任何数字、字段名、配置项、人名、流程，只要【参考文档】里没有，就绝对不能写，也不要用你自己的知识补充。
                2. 逐句举证：回答里每一条事实性陈述，都必须在句末标注其来源，格式「（见【文档N】）」；无法标注来源的陈述不许出现。
                3. 冲突处理：若不同文档说法冲突，分别列出并各自标注出处，不要自己选边。
                4. 不知就说不知：若【参考文档】完全未覆盖用户问题，只能回答「知识库中未找到相关信息」，严禁硬凑或猜测。
                5. 回答简洁、用中文、分点清晰。
                """;
        // 📌 S2 事实核查员客户端：始终先构建（system=GATE），随后主生成客户端会覆盖 builder 的 system，互不影响
        this.gateClient = chatClientBuilder
                .defaultSystem("""
                        你是严格的事实核查员。任务：核对【模型回答】里的每一条事实性陈述是否能在【参考文档】中找到依据。
                        规则：① 能在文档中找到依据的陈述，原样保留；② 文档中完全找不到依据的陈述（属于编造），整句删除；
                        ③ 若所有陈述都无依据，只输出「知识库中未找到相关信息」；④ 不要添加解释、不要编造新内容。
                        只输出核查后的最终回答文本。
                        """)
                .build();
        // 🟢 默认 v0；scheme1=true 切到 S1 强 prompt + temperature 0.1（注意：0.1 曾在 300/150 改坏 HyDE 检索，此处如实开启）
        ChatClient.Builder genBuilder = chatClientBuilder
                .defaultSystem(scheme1Enabled ? s1Prompt : v0Prompt);
        if (scheme1Enabled) {
            genBuilder = genBuilder.defaultOptions(
                    ChatOptions.builder().temperature(0.1).build());
        }
        this.chatClient = genBuilder.build();

        // 🟡 [S1 已实现为 yml 开关] 强约束 prompt + temperature 0.1 现已由构造器 scheme1Enabled 切换，
        //    不再用注释代码。历史实测（300/150）：faithfulness 0.516→0.398、recall 0.577→0.497，净负面，
        //    根因 = 0.1 改坏 HyDE 检索。500/150 下是否逆转待本次 RAGAS 验证（scheme1-enabled=true 开启）。
    }

    /** S3 开关：返回实际 topK（scheme3 开启 → 10，否则用默认 6） */
    private int effectiveTopK() {
        return scheme3Enabled ? 10 : topK;
    }

    /**
     * 初始化向量库（@PostConstruct：此时 @Value 字段已注入完成）
     * -------------------------------------------------------------------
     * ⚠️ 关键修复点（PgVector 经典坑）：
     *   PgVectorStore 是在本类构造函数里用 builder 手搓的「普通对象」，不是 Spring 托管的 @Bean，
     *   因此 Spring 不会自动调用它的 afterPropertiesSet()，而「建表 + 建 HNSW 索引」的逻辑
     *   恰恰就写在 afterPropertiesSet() 里（initializeSchema=true 只是个开关，不会自己建表）。
     *   → 不手动触发，chat_document 表永远建不出来 → 阶段3 入库报「关系不存在」(500)。
     *   这里手动调一次 afterPropertiesSet()，保证表就绪。
     * ✅ 把建表放 @PostConstruct 还有个好处：@Value 已注入，下面的日志能打印真实配置值
     *   （不再像构造函数里那样因为字段还没注入而打印出 0 的假象）。
     */
    @PostConstruct
    public void initVectorStore() throws Exception {
        // 📌 阶段3 存储：自建一张独立 PGVector 表（与 vector_store / chat_memory 三表独立，互不污染）
        this.vectorStore = PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("chat_document")
                .dimensions(1536)   // 对齐 MiniMax embo-01（1536 维），yml 同值
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .build();

        // 📌 核心修复：手动触发建表（Spring 不会为手搓对象自动调用）
        this.vectorStore.afterPropertiesSet();

        // 📌 索引调优：用本类 DataSource 构造事务模板，检索前用它设 hnsw.ef_search（见 vectorSearchWithEf）
        this.transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(jdbcTemplate.getDataSource()));

        log.info("[EnterpriseRag] 企业级 RAG 链路就绪：chunkSize={}, overlap={}, topK={}, threshold={}",
                chunkSize, overlap, effectiveTopK(), similarityThreshold);
    }

    // =====================================================================
    // 对外 HTTP 端点（方便直接 curl 测试，风格与项目其他 Controller 一致）
    // =====================================================================

    /**
     * 灌库：把一段文本走完 阶段1(分块) → 阶段3(存储，内部含阶段2向量化)
     * POST /ai/rag/enterprise/ingest
     * Body: {"text":"...","kb":"hr","title":"员工手册"}
     */
    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestBody Map<String, String> req) {
        String text = req.get("text");
        String kb = req.getOrDefault("kb", "default");
        String title = req.getOrDefault("title", "未命名文档");
        if (text == null || text.isBlank()) {
            return Map.of("success", false, "message", "text 不能为空");
        }
        int n = ingestDocument(text, kb, title);
        return Map.of("success", true, "kb", kb, "chunks", n,
                "message", "已分块并入库 chat_document");
    }

    /**
     * 问答：走完 阶段4(检索) → 阶段5(生成)
     * POST /ai/rag/enterprise/ask
     * Body: {"question":"年假几天？","kb":"hr"}
     */
    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody Map<String, Object> req) {
        String question = (String) req.get("question");
        String kb = req.get("kb") instanceof String s ? s : "default";
        if (question == null || question.isBlank()) {
            return Map.of("success", false, "message", "question 不能为空");
        }
        // 📌 企业做法：历史存在后端记忆表，前端只传 sessionId；不传=单轮，不读写记忆
        String sessionId = req.get("sessionId") instanceof String s ? s : null;
        RagResult result = ask(question, kb, sessionId);

        // 📌 全量原文：把所有检索到的文档按「文档N · 标题 · 段落X」可读格式拼出来，不做任何截断
        //    用于前端直接展示检索上下文（对应前端 ims-test.html 去掉截断后的全量诉求）
        StringBuilder rawCtx = new StringBuilder();
        List<SourceRef> srcs = result.sources();
        for (int i = 0; i < srcs.size(); i++) {
            SourceRef s = srcs.get(i);
            rawCtx.append("文档").append(i + 1).append(" · ")
                   .append(s.title()).append(" · 段落").append(s.chunkIndex()).append("\n")
                   .append(s.snippet()).append("\n\n");
        }

        // ⚠️ 改用 Map.ofEntries：原 Map.of 已超过 10 参数上限（编译不过），ofEntries 无此限制且便于扩展
        return Map.ofEntries(
                Map.entry("success", true),
                Map.entry("question", question),
                Map.entry("answer", result.answer()),
                Map.entry("retrievedDocs", result.sources().size()),
                Map.entry("sessionId", sessionId == null ? "" : sessionId),
                Map.entry("sources", result.sources()),
                Map.entry("rawContext", rawCtx.toString()));
    }

    /** 记忆表会话前缀：与 IMS 对话（默认无前缀）隔离，复用同一张 SPRING_AI_CHAT_MEMORY 表 */
    private static final String RAG_CONV_PREFIX = "rag-";

    /** 从 JDBC 记忆表读最近的用户问题（旧→新），作为 query 改写的历史上下文 */
    private List<String> loadHistory(String sessionId) {
        String cid = RAG_CONV_PREFIX + sessionId;
        List<Message> msgs = chatMemory.get(cid);   // 窗口内全部消息（ChatMemory Bean 已按 maxMessages=10 限制为最近 10 条）
        List<String> questions = new ArrayList<>();
        for (Message m : msgs) {
            if (m instanceof UserMessage um) {
                questions.add(um.getText());
            }
        }
        return questions;   // 已是时间顺序（旧→新）
    }

    /**
     * 阶段2 显式演示：把任意文本向量化，返回维度 + 前 8 维，肉眼看「文本→向量」长什么样
     * GET /ai/rag/enterprise/embed-demo?text=年假规定
     */
    @GetMapping("/embed-demo")
    public Map<String, Object> embedDemo(@RequestParam(defaultValue = "企业年假制度") String text) {
        // 📌 这一步就是「阶段2 向量化」的本质：文本 → 浮点数组（bge-m3 输出 1024 维）
        float[] vec = embeddingModel.embed(text);
        float[] head = Arrays.copyOfRange(vec, 0, Math.min(8, vec.length));
        return Map.of("text", text, "dimension", vec.length,
                "first8", Arrays.toString(head));
    }

    /** 健康检查 + 当前配置，确认链路已起来 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "vectorTable", "chat_document",
                "chunkSize", chunkSize, "overlap", overlap, "topK", effectiveTopK(),
                "similarityThreshold", similarityThreshold);
    }

    /** 一键灌入演示知识库（公司制度），免去手敲文本 */
    @PostMapping("/init-demo")
    public Map<String, Object> initDemo() {
        List<String> docs = List.of(
                "公司年假制度：入职满1年不满10年的员工，年休假5天；满10年不满20年的，年休假10天；满20年的，年休假15天。",
                "加班申请流程：员工需提前填写加班申请单，经直属上级审批后提交人力资源部备案。",
                "病假规定：请病假需当天9点前通过OA系统提交，并上传医院诊断证明。",
                "办公时间：周一至周五 9:00-12:00，13:00-18:00。迟到超过30分钟记旷工半天。",
                "IMS企业管理系统功能包括：员工管理、考勤管理、薪资管理、审批流程。"
        );
        int total = 0;
        for (String d : docs) total += ingestDocument(d, "hr", "员工手册片段");
        return Map.of("success", true, "message", "演示知识库初始化完成", "chunks", total);
    }

    // =====================================================================
    // 五阶段方法（对外也能单独调用，方便面试讲解时逐个拆开看）
    // =====================================================================

    /**
     * 阶段1：分块 Chunking
     * -------------------------------------------------------------------
     * 企业落地要点：
     *  - 不要无脑按固定字数切（会切断语义）；先做「递归字符分割」：段落→句子→超长单句硬切。
     *  - 必须有 overlap（相邻块重叠一段），否则一个完整语义被切在边界时，检索只能命中半截。
     *  - 每块打 metadata：kb / title / chunkIndex / charCount，后面检索过滤与引用都靠它。
     *
     * @return 已带 metadata 的 Document 列表（id 用 UUID，PGVector 要求 id 为 UUID）
     */
    public List<Document> stage1Chunking(String text, String kb, String title) {
        // 📌 第1步：切出「原子片段」——按句末标点(。！？；)和换行断句，超长单句再按字符硬切(带 overlap 步进)
        List<String> atomic = new ArrayList<>();
        for (String raw : text.split("(?<=[。！？!?；;\n])")) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            if (s.length() > chunkSize) {
                // ⚠️ 单句就超长：按 (chunkSize - overlap) 步进硬切，形成字符级重叠
                for (int p = 0; p < s.length(); p += (chunkSize - overlap)) {
                    atomic.add(s.substring(p, Math.min(p + chunkSize, s.length())));
                }
            } else {
                atomic.add(s);
            }
        }

        // 📌 第2步：贪心合并原子片段成块，且相邻块「回退一句」形成句子级重叠
        List<Document> chunks = new ArrayList<>();
        int i = 0;
        int idx = 0;
        while (i < atomic.size()) {
            StringBuilder sb = new StringBuilder();
            int j = i;
            while (j < atomic.size() && sb.length() + atomic.get(j).length() <= chunkSize) {
                sb.append(atomic.get(j));
                j++;
            }
            if (sb.length() == 0) {           // 理论不会进（已硬切），兜底
                chunks.add(buildDoc(atomic.get(i), kb, title, idx++));
                i++;
            } else {
                chunks.add(buildDoc(sb.toString(), kb, title, idx++));
                // ✅ 关键：下一步从 j-1 开始 → 最后一句在下一块重复出现 = 重叠，保语义连续
                i = Math.max(i + 1, j - 1);
            }
        }
        log.info("[阶段1 分块] 原文 {} 字符 → 切成 {} 块（chunkSize={}, overlap={}）",
                text.length(), chunks.size(), chunkSize, overlap);
        return chunks;
    }

    /**
     * 阶段2：向量化 Embedding（显式演示版）
     * -------------------------------------------------------------------
     * 说明：生产里向量化通常由阶段3的 vectorStore.add() 在入库时「自动内部调用」，
     * 这里单独抽出来，是为了让你看清「文本 → 1024 维向量」这一步到底发生了什么。
     * bge-m3 是中文检索首选，输出固定 1024 维，必须与存储维度对齐。
     */
    public float[] stage2Embedding(String text) {
        // 📌 一行就是核心：EmbeddingModel 把任意文本压成一个定长浮点向量
        float[] vector = embeddingModel.embed(text);
        log.info("[阶段2 向量化] 「{}」→ {} 维向量", text, vector.length);
        return vector;
    }

    /**
     * 阶段3：存储 Storage
     * -------------------------------------------------------------------
     * 把分好块、带 metadata 的 Document 批量写进 PGVector。
     * ⚠️ 内部会自动对每块调用 EmbeddingModel 向量化（即阶段2在入库时真正发生），
     *    所以「阶段2向量化」在生产中一般不单独调，而是被这一步顺带完成。
     * ✅ PGVector 持久化：重启不丢、支持 HNSW 索引做近似最近邻，适合企业级规模。
     */
    public void stage3Storage(List<Document> chunks) {
        if (chunks.isEmpty()) return;
        // 📌 这一行完成「向量化 + 写库」：底层 = 对每块 embed() 后 INSERT 进 chat_document（与 initVectorStore 配置的 vectorTableName 一致）
        vectorStore.add(chunks);
        log.info("[阶段3 存储] 已写入 {} 个向量块到 PGVector(chat_document)", chunks.size());
    }

    /**
     * 阶段4：检索 Retrieval
     * -------------------------------------------------------------------
     * 企业落地要点（对照简化版缺的三样）：
     *  - metadata 过滤：只在本 kb 内检索，避免「hr 的问题」召回「技术文档」。
     *  - 相似度阈值：低于阈值的噪声结果直接丢，否则会污染 Prompt。
     *  - 重排 rerank：召回 topK*2 候选后，用语义排名 + 词面重合做混合重排，精选 topK。
     *    （生产可用 bge-reranker 这类 cross-encoder 替代下面的简化实现）
     *
     * @return 重排后、最相关的 topK 个 Document（带出处 metadata）
     */
    public List<Document> stage4Retrieval(String question, String kb, List<String> history) {
        long retrievalStart = System.nanoTime();
        // 📌 第0步：查询改写（企业逻辑：仅多轮有 history 时补全指代；单轮透传，省一次 LLM 调用）
        String searchQuery = queryAugmenter.rewrite(question, history);
        if (!searchQuery.equals(question)) {
            log.info("[阶段4] 查询改写生效: {} -> {}", question, searchQuery);
        }

        // 📌 第1步：构造 metadata 过滤表达式 —— 只在指定知识库内找
        // ⚠️ Spring AI 1.0.0 的过滤构建器是 FilterExpressionBuilder（不是 Filter.Builder）
        Filter.Expression filter = new FilterExpressionBuilder().eq("kb", kb).build();

        // 📌 第2步：语义召回（用 searchQuery，问题自动被向量化），多取 topK*2 留给 rerank 精选
        List<Document> vectorCands = vectorSearchWithEf(SearchRequest.builder()
                .query(searchQuery)
                .topK(effectiveTopK() * 2)
                .similarityThreshold(similarityThreshold)   // ⚠️ 丢弃低相关噪声
                .filterExpression(filter)                   // ⚠️ 知识库隔离
                .build());

        // 📌 第2.5步：HyDE 兜底（企业逻辑：向量 top1 偏弱才调 LLM 生成假设文档再搜一次）
        //    HyDE 把「假设文档文本」喂给 similaritySearch（内部 embed 假设文档），使其向量接近知识库文档分布
        List<Document> hydeDocs = List.of();
        double topScore = vectorCands.isEmpty() ? 0.0 : vectorCands.get(0).getScore();
        if (queryAugmenter.shouldUseHyde(topScore, searchQuery)) {
            String hypothesis = queryAugmenter.hyde(searchQuery);
            hydeDocs = vectorSearchWithEf(SearchRequest.builder()
                    .query(hypothesis)
                    .topK(effectiveTopK() * 2)
                    .similarityThreshold(similarityThreshold)
                    .filterExpression(filter)
                    .build());
            log.info("[阶段4] HyDE 兜底召回 {} 条（向量 top1 score={}）", hydeDocs.size(), topScore);
        }

        // 📌 第2.6步：语义路 = 向量召回 + HyDE 召回（同一路，按 id 由 RRF 自动去重/累加）
        List<Document> semanticCands = new ArrayList<>(vectorCands);
        semanticCands.addAll(hydeDocs);

        // 📌 第3步：字面召回（BM25 路，用 searchQuery）—— 补向量路漏掉的「编号/专有名词精确命中」
        //   例如问「订单 IMS-2024-001」，向量路语义相近但字面不同可能漏，BM25 靠字面对齐命中
        List<Bm25Retriever.Hit> bm25Hits = bm25.retrieve(searchQuery, kb, effectiveTopK() * 2);

        // 📌 第4步：两路 RRF 融合（语义路 + BM25 路）—— 不比两路分数绝对值，只按各自排名融合，谁靠前谁加分
        List<Document> fused = rrfFuse(semanticCands, bm25Hits, effectiveTopK() * 2);
        log.info("[阶段4 检索] 向量{} + HyDE{} + BM25{} → 融合 {} 候选（kb={}），准备 rerank 精选 top{}",
                vectorCands.size(), hydeDocs.size(), bm25Hits.size(), fused.size(), kb, effectiveTopK());

        // 📌 第5步：Cross-Encoder 重排（真实 bge-reranker 精排，失败时回退简化版），返回最终 topK
        //   ⚠️ rerank 用 searchQuery（与检索一致）；最终生成 stage5Generation 仍用原始 question（保持用户意图）
        //   📌 成本控制：rerank 只对「召回 topK*2=12 个候选」打分，不是全库 2878 块 → 贵模型限制在少量候选上
        List<Document> retrieved = rerank(searchQuery, fused, effectiveTopK());
        ragMetrics.recordRetrieval(System.nanoTime() - retrievalStart);
        return retrieved;
    }

    /**
     * 带 ef_search 调优的向量召回
     * -------------------------------------------------------------------
     * pgvector 的 hnsw.ef_search 是【查询阶段】旋钮，控制搜索时候选列表大小：
     * 调大 -> HNSW 图遍历更彻底 -> 召回率(context_recall)升，但延迟(latency)涨。
     * 关键点：SET LOCAL 只在「同一事务/连接」内生效，所以必须和 similaritySearch
     *   包在同一个 Spring 事务里（用 transactionTemplate 绑定同一连接），否则 SET 不生效。
     * 我们不改 M / ef_construction（那是构建期参数，改了要 DROP 索引重建），只动这个热插拔旋钮。
     */
    private List<Document> vectorSearchWithEf(SearchRequest request) {
        return transactionTemplate.execute(status -> {
            // 在同一连接里先设 ef_search，再召回；事务结束连接归还，SET 随之失效（不污染别的查询）
            jdbcTemplate.execute("SET LOCAL hnsw.ef_search = " + efSearch);
            return vectorStore.similaritySearch(request);
        });
    }

    /**
     * RRF（Reciprocal Rank Fusion，倒数排名融合）
     * -------------------------------------------------------------------
     * 向量路与 BM25 路打分体系不同（一个 cosine 距离、一个 TF-IDF 派生），不能直接比大小。
     * RRF 只取每路各自的「排名」：第 rank 名得 1/(K+rank) 分（K=60 是经验常数），
     * 同一文档两路都靠前 → 总分高 → 融合后靠前。最后按总分排序取前 limit 个。
     * 两路命中同一文档时按 id 去重合并（向量路的 Document 带完整 metadata，优先保留）。
     */
    private List<Document> rrfFuse(List<Document> vectorCands, List<Bm25Retriever.Hit> bm25Hits, int limit) {
        final double K = 60.0;
        Map<String, Double> scoreMap = new LinkedHashMap<>();
        Map<String, Document> docMap = new HashMap<>();

        // 向量路：按召回排名累加 RRF 分
        for (int rank = 0; rank < vectorCands.size(); rank++) {
            Document d = vectorCands.get(rank);
            scoreMap.merge(d.getId(), 1.0 / (K + rank + 1), Double::sum);
            docMap.putIfAbsent(d.getId(), d);   // 优先保留向量路带完整 metadata 的 Document
        }
        // BM25 路：同样按排名累加；若 id 与向量路重合则自动合并，否则构造最小 Document 兜底
        for (int rank = 0; rank < bm25Hits.size(); rank++) {
            Bm25Retriever.Hit h = bm25Hits.get(rank);
            scoreMap.merge(h.id, 1.0 / (K + rank + 1), Double::sum);
            docMap.putIfAbsent(h.id, new Document(h.id, h.text, defaultMeta(h.kb)));
        }
        // 按 RRF 总分降序取前 limit
        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> docMap.get(e.getKey()))
                .toList();
    }

    /** BM25 独有命中（向量路没召回）兜底用的 metadata */
    private Map<String, Object> defaultMeta(String kb) {
        Map<String, Object> m = new HashMap<>();
        m.put("kb", kb);
        m.put("title", "BM25字面召回");
        m.put("source", "bm25");
        return m;
    }

    /**
     * 阶段5：生成 Generation（带引用的接地回答）
     * -------------------------------------------------------------------
     * 企业落地要点：
     *  - 把检索到的上下文 + 问题拼成 Prompt，要求 LLM「只基于文档、标注出处」。
     *  - 系统人设里写了护栏：找不到就说找不到，禁止编造（解决 RAG 幻觉）。
     *  - 返回 answer + 来源列表，前端可展示「引用了哪些文档」，可审计、可溯源。
     */
    public RagResult stage5Generation(String question, List<Document> context) {
        // 📌 第1步：把检索结果拼成带编号的上下文（编号用于让模型标注【文档N】出处）
        StringBuilder ctx = new StringBuilder();
        List<SourceRef> sources = new ArrayList<>();
        for (int i = 0; i < context.size(); i++) {
            Document d = context.get(i);
            String title = String.valueOf(d.getMetadata().getOrDefault("title", "未知"));
            String chunkIdx = String.valueOf(d.getMetadata().getOrDefault("chunkIndex", i));
            ctx.append("【文档").append(i + 1).append("】(来源:").append(title)
               .append(", 段落").append(chunkIdx).append(")\n")
               .append(d.getText()).append("\n\n");
            sources.add(new SourceRef(title, chunkIdx, d.getText()));
        }

        // 📌 第2步：组装「接地」Prompt（用 StringBuilder 拼，避免用户问句含 % 触发 format 异常）
        StringBuilder prompt = new StringBuilder();
        prompt.append("【参考文档】\n").append(ctx).append("\n");
        prompt.append("【用户问题】").append(question).append("\n");
        prompt.append("请只使用上面的【参考文档】作答。要求：\n")
              .append("① 每条事实性内容都要标注来源（见【文档N】）；\n")
              .append("② 文档里没有的信息绝不写入，也不要用你自己的知识补充；\n")
              .append("③ 若文档不足以回答，只回复「知识库中未找到相关信息」。\n【回答】");

        // 📌 第3步：调用 LLM（人设里的护栏在此生效：只认文档、找不到明说）
        ChatResponse genResp = chatClient.prompt()
                .user(prompt.toString())
                .call()
                .chatResponse();
        // 📌 可观测性：记录本次生成的 token 消耗（input=提问侧, output=回答侧 → 直接对应 LLM 服务商 计费）
        Usage usage = genResp.getMetadata().getUsage();
        if (usage != null) {
            ragMetrics.recordTokens(usage.getPromptTokens(), usage.getCompletionTokens());
        }
        String answer = genResp.getResult().getOutput().getText();

        // 📌 清洗模型自带的 <think> 思维链标签（MiniMax M3 等推理模型会输出），
        //    避免把内部推理过程泄露给终端用户，保持生成内容干净、可直接展示。
        answer = answer.replaceAll("<think>[\\s\\S]*?</think>", "").trim();

        log.info("[阶段5 生成] 基于 {} 个文档作答，长度 {} 字符", context.size(), answer.length());
        return new RagResult(answer, sources);
    }

    /**
     * 🔴 S2 生成后 faithfulness(忠实度) 护栏（Self-Check Guardrail / 自我核查护栏）
     * -------------------------------------------------------------------
     * 生成答案后，再用「事实核查员」(gateClient) 把答案逐句与检索到的 context 比对，
     * 删掉文档里找不到依据（=模型编造）的句子；全无依据则退化成原答案（兜底防 over-deletion）。
     * 本质 = 把 RAGAS 的 faithfulness 指标从「离线评测」搬成「线上实时护栏」（生产级降幻觉手段）。
     */
    private String faithfulnessGate(String question, String answer, List<Document> context) {
        if (answer == null || answer.isBlank()) return answer;
        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < context.size(); i++) {
            Document d = context.get(i);
            String title = String.valueOf(d.getMetadata().getOrDefault("title", "未知"));
            ctx.append("【文档").append(i + 1).append("】(来源:").append(title).append(")\n")
               .append(d.getText()).append("\n\n");
        }
        String prompt = "【参考文档】\n" + ctx + "\n【用户问题】" + question
                + "\n【模型回答】\n" + answer + "\n\n请按你的规则核查，只输出核查后的最终回答：";
        try {
            String cleaned = gateClient.prompt().user(prompt).call().content();
            cleaned = cleaned.replaceAll("<think>[\\s\\S]*?</think>", "").trim();
            return cleaned.isBlank() ? answer : cleaned;   // ⚠️ 兜底：删空则退回原答案
        } catch (Exception e) {
            log.warn("[S2 护栏] 核查调用异常，退回原答案：{}", e.getMessage());
            return answer;
        }
    }

    // =====================================================================
    // 编排方法：把五个阶段串成两条业务线（灌库线 / 问答线）
    // =====================================================================

    /** 灌库线：阶段1(分块) → 阶段3(存储，内部含阶段2向量化) */
    public int ingestDocument(String text, String kb, String title) {
        List<Document> chunks = stage1Chunking(text, kb, title);
        // 📌 混合检索：入库同时把每块同步灌进 BM25 内存索引（id 与向量库同一批，融合时才能按 id 对齐去重）
        for (Document d : chunks) {
            bm25.add(d.getId(), d.getText(), kb);
        }
        stage3Storage(chunks);
        return chunks.size();
    }

    /** 问答线（单轮便捷版）：不读写记忆，适合简单 curl/单轮测试 */
    public RagResult ask(String question, String kb) {
        return ask(question, kb, null);
    }

    /**
     * 问答线（多轮版，企业默认）：会话历史从 JDBC 记忆表按 sessionId 读取，
     * 生成后把「用户问题 + AI 回答」写回记忆表，供下一轮改写使用。
     * sessionId 为 null/空 → 退化为单轮（不读写记忆）。
     */
    public RagResult ask(String question, String kb, String sessionId) {
        long startNanos = System.nanoTime();
        try {
            // 📌 第-1步：从后端记忆表读历史（企业做法：历史在服务端，前端只传 sessionId）
            List<String> history = (sessionId != null && !sessionId.isBlank())
                    ? loadHistory(sessionId) : List.of();
            List<Document> context = stage4Retrieval(question, kb, history);
            if (context.isEmpty()) {
                return new RagResult("知识库中未找到相关信息（召回为空或均低于相似度阈值）。",
                        List.of());
            }
            // ⚠️ 最终生成用原始 question（不是 searchQuery）：检索帮「找得准」，但回答要贴合「用户原意」
            RagResult result = stage5Generation(question, context);
            // 🔴 S2 生成后 faithfulness(忠实度) 护栏：仅在 scheme2 开启时，把答案送事实核查员删无依据句（含删空兜底）
            if (scheme2Enabled) {
                result = new RagResult(
                        faithfulnessGate(question, result.answer(), context),
                        result.sources());
            }
            // 📌 写回记忆：用 "rag-" 前缀隔离，和 IMS 对话不在同一会话串味
            if (sessionId != null && !sessionId.isBlank()) {
                String cid = RAG_CONV_PREFIX + sessionId;
                chatMemory.add(cid, new UserMessage(question));
                chatMemory.add(cid, new AssistantMessage(result.answer()));
            }
            return result;
        } catch (Exception e) {
            throw e;
        } finally {
            ragMetrics.recordAsk(System.nanoTime() - startNanos);
        }
    }

    /*
     * ============================================================================
     * 🟡 [S2 现已 yml 开关化] 生成后 faithfulness(忠实度) 护栏（Self-Check Guardrail / 自我核查护栏）
     * ============================================================================
     * 思路：生成答案后，再用一个「事实核查员」LLM 把答案逐句与检索到的 context 比对，
     *      删掉文档里找不到依据（=模型编造）的句子；全无依据则退化成「知识库中未找到相关信息」。
     *      本质 = 把 RAGAS 的 faithfulness 指标从「离线评测」搬成「线上实时护栏」
     *      （生产级降幻觉最高杠杆手段，也是把离线评测做成线上护栏的典型范式）。
     * 实测（在 S1 基础上加，RAGAS 9 题）：
     *   faithfulness(忠实度)     0.398 → 0.424  ↑0.026（仅微升）
     *   context_recall(召回率)   0.497 → 0.377  ↓0.120（检索代码未改，纯属 LLM-judge 方差）
     *   answer_relevancy(相关性) 0.605 → 0.579  ↓0.026
     * 结论：净负面。灾难点 = 「删无引用句」逻辑过度删除(over-deletion)：日志显示 Q7 答案被砍到仅 11 字，
     *      有效内容也清空。经典反模式 —— 护栏必须「保留有依据句 + 只删真正无依据句」+ 兜底（删空则退回原答案）。
     *      下方参考实现现已落地为真实 faithfulnessGate() 方法（位于本类，含「删空则退回原答案」兜底），
     *      由 scheme2-enabled 开关控制（默认关）。历史实测（300/150）：F 0.398→0.424 微升、CR 0.497→0.377（纯 judge 方差），净负面因 over-deletion；现加兜底后应更安全。500/150 待验证。
     * ----------------------------------------------------------------------------
     * // 构造器内新增字段与 builder：
     * private final ChatClient gateClient;
     * this.gateClient = chatClientBuilder
     *         .defaultSystem("""
     *                 你是严格的事实核查员。任务：核对【模型回答】里的每一条事实性陈述是否能在【参考文档】中找到依据。
     *                 规则：① 能在文档中找到依据的陈述，原样保留；② 文档中完全找不到依据的陈述（属于编造），整句删除；
     *                 ③ 若所有陈述都无依据，只输出「知识库中未找到相关信息」；④ 不要添加解释、不要编造新内容。
     *                 只输出核查后的最终回答文本。
     *                 """)
     *         .build();
     *
     * // ask 方法里，stage5Generation 之后包一层：
     * RagResult generated = stage5Generation(question, context);
     * String groundedAnswer = faithfulnessGate(question, generated.answer(), context);
     * RagResult result = new RagResult(groundedAnswer, generated.sources());
     *
     * // 方法实现：
     * private String faithfulnessGate(String question, String answer, List<Document> context) {
     *     if (answer == null || answer.isBlank()) return answer;
     *     StringBuilder ctx = new StringBuilder();
     *     for (int i = 0; i < context.size(); i++) {
     *         Document d = context.get(i);
     *         String title = String.valueOf(d.getMetadata().getOrDefault("title", "未知"));
     *         ctx.append("【文档").append(i + 1).append("】(来源:").append(title).append(")\n")
     *            .append(d.getText()).append("\n\n");
     *     }
     *     String prompt = "【参考文档】\n" + ctx + "\n【用户问题】" + question
     *             + "\n【模型回答】\n" + answer + "\n\n请按你的规则核查，只输出核查后的最终回答：";
     *     try {
     *         String cleaned = gateClient.prompt().user(prompt).call().content();
     *         cleaned = cleaned.replaceAll("<think>[\\s\\S]*?</think>", "").trim();
     *         return cleaned.isBlank() ? answer : cleaned;   // ⚠️ 兜底：删空则退回原答案
     *     } catch (Exception e) {
     *         return answer;
     *     }
     * }
     * ============================================================================
     */

    // =====================================================================
    // 内部工具
    // =====================================================================

    /** 构造带 UUID id 与标准 metadata 的 Document（PGVector 要求 id 为 UUID） */
    private Document buildDoc(String text, String kb, String title, int idx) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("kb", kb);                 // 知识库隔离键（检索过滤用）
        metadata.put("title", title);           // 出处标题（引用展示用）
        metadata.put("chunkIndex", idx);        // 段落序号
        metadata.put("charCount", text.length());
        return new Document(UUID.randomUUID().toString(), text, metadata);
    }

    /**
     * 简化版混合重排（hybrid rerank）
     * -------------------------------------------------------------------
     * 思路：语义召回的排名越靠前 → 语义分越高；再叠加「查询词在文档中的字面重合度」。
     * 目的：把「语义分高但字面完全不沾边」的噪声块往后挤，提升最终注入 Prompt 的质量。
     * ⚠️ 这是教学版；生产环境应替换为 cross-encoder 重排模型（如 BAAI/bge-reranker-v2）。
     */
    /**
     * 📌 C 方案：真实 Cross-Encoder 重排序（生产级 reranker）
     * ===================================================================
     * 把「教学版简化 rerank（语义排名 + 字面重合）」升级为 SiliconFlow 的
     * BAAI/bge-reranker-v2-m3（cross-encoder 交叉编码器）API。
     *
     * 为什么比简化版强：
     *   - 简化版只看「召回排名 + 字面重合」，不懂语义；
     *   - cross-encoder 把 (query, doc) 一起喂进模型做「交互式」打分，能捕捉
     *     "问的是 A 但字面是 B" 这类语义相关性，精排更准 → 提升 context_precision / context_recall。
     *
     * ⚠️ 成本控制（这是 #5 reranker 的核心考点）：
     *   - rerank 只对「召回阶段的 topK*2（当前=12）个候选」打分，不是对全库 2878 块；
     *   - 一次 HTTP 调用搞定 12 个候选，延迟约几百 ms，费用按 token 计极低；
     *   - 即「粗排（向量+BM25）召回多 → 精排（cross-encoder）精选少」两段式，
     *     把贵的精排限制在少量候选上，这是企业标准做法。
     *
     * 容错：API 调用异常/超时时自动回退到原简化 rerank，保证检索不崩。
     */
    private List<Document> rerank(String query, List<Document> candidates, int topK) {
        if (candidates.isEmpty()) return candidates;
        try {
            return crossEncoderRerank(query, candidates, topK);
        } catch (Exception e) {
            log.warn("[rerank] cross-encoder 调用失败，回退简化版 rerank: {}", e.getMessage());
            return fallbackRerank(query, candidates, topK);
        }
    }

    /** 真实 cross-encoder 重排：调 SiliconFlow /v1/rerank（BAAI/bge-reranker-v2-m3） */
    private List<Document> crossEncoderRerank(String query, List<Document> candidates, int topK) {
        // 1) 拼请求体（用 Jackson 序列化，避免手写 JSON 转义）
        List<String> docs = candidates.stream().map(Document::getText).toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", RERANK_MODEL);
        payload.put("query", query);
        payload.put("documents", docs);
        payload.put("return_documents", false);
        payload.put("top_n", Math.min(topK, candidates.size()));
        String body;
        try {
            body = rerankObjectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("rerank 请求体序列化失败: " + e.getMessage());
        }
        // 2) 发 HTTP（连接 5s / 读取 15s 超时，超时即回退简化版）
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(RERANK_URL))
                .header("Authorization", "Bearer " + siliconflowApiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<String> resp;
        try {
            resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("rerank HTTP 调用失败: " + e.getMessage());
        }
        if (resp.statusCode() != 200) {
            throw new RuntimeException("rerank HTTP " + resp.statusCode() + ": " + resp.body());
        }
        // 3) 解析 results（index + relevance_score），按分数降序回查原 Document
        JsonNode root;
        try {
            root = rerankObjectMapper.readTree(resp.body());
        } catch (Exception e) {
            throw new RuntimeException("rerank 响应解析失败: " + e.getMessage());
        }
        ArrayNode results = (ArrayNode) root.get("results");
        List<IndexedScore> scored = new ArrayList<>();
        for (JsonNode r : results) {
            scored.add(new IndexedScore(r.get("index").asInt(), r.get("relevance_score").asDouble()));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));   // 降序（API 已降序，保险再排）

        // 📌 P0 修复：动态 topK —— 不再"硬取满 topK"，改为「数量上限 + 相关性分数下限」双截断
        //    截断线 = max(绝对阈值, top1分数 × 相对比例)，两者取严；并保底 rerankMinDocs 块防全空。
        double topScore = scored.isEmpty() ? 0.0 : scored.get(0).score;
        double cutoff = Math.max(rerankScoreThreshold, topScore * rerankRelativeRatio);

        List<Document> out = new ArrayList<>();
        int dropped = 0;
        for (IndexedScore s : scored) {
            if (s.idx < 0 || s.idx >= candidates.size()) continue;
            if (out.size() >= topK) break;                       // ① 数量上限（原有逻辑）
            if (s.score < cutoff && out.size() >= rerankMinDocs) {  // ② 分数下限（新增），但保底不砍空
                dropped++;
                continue;
            }
            out.add(candidates.get(s.idx));
        }
        log.info("[rerank] cross-encoder 精排：候选{} → 实际返回{}（上限{}，截断线{}=max(绝对{}, top1 {}×{}), 按分数丢弃{}）",
                candidates.size(), out.size(), topK, String.format("%.3f", cutoff),
                rerankScoreThreshold, String.format("%.3f", topScore), rerankRelativeRatio, dropped);
        return out;
    }

    /** 原教学版 rerank（作为 cross-encoder 的容错回退，避免 API 抖动导致检索全空） */
    private List<Document> fallbackRerank(String query, List<Document> candidates, int topK) {
        Set<String> qTerms = tokenize(query);
        int n = candidates.size();
        List<ScoredDoc> scored = new ArrayList<>();
        for (int rank = 0; rank < n; rank++) {
            Document d = candidates.get(rank);
            double semantic = 1.0 - (double) rank / Math.max(n, 1);   // 召回排名 → 语义分
            long hit = qTerms.stream().filter(t -> d.getText().contains(t)).count();
            double lexical = qTerms.isEmpty() ? 0.0 : (double) hit / qTerms.size(); // 字面重合占比
            double finalScore = 0.7 * semantic + 0.3 * lexical;        // 语义为主、词法为辅
            scored.add(new ScoredDoc(d, finalScore));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));      // 按综合分降序

        // 📌 P0 修复：回退路径同样做动态截断，保证 cross-encoder 抖动时 precision 不退化。
        //    注意这里分数是 0~1 的加权分（语义0.7+词法0.3），尺度与 cross-encoder 不同，
        //    所以只用「相对阈值」，不套用绝对阈值（绝对阈值是给 cross-encoder 尺度定的）。
        double topScore = scored.isEmpty() ? 0.0 : scored.get(0).score;
        double cutoff = topScore * rerankRelativeRatio;
        List<Document> out = new ArrayList<>();
        for (ScoredDoc sd : scored) {
            if (out.size() >= topK) break;
            if (sd.score < cutoff && out.size() >= rerankMinDocs) continue;
            out.add(sd.doc);
        }
        log.info("[rerank-fallback] 简化重排：候选{} → 实际返回{}（上限{}，相对截断线{}）",
                candidates.size(), out.size(), topK, String.format("%.3f", cutoff));
        return out;
    }

    /** 极简分词：中文按单字、英文数字按完整词，用于词面重合度计算 */
    private Set<String> tokenize(String s) {
        Set<String> set = new HashSet<>();
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c > 0x4E00) {  // 含汉字
                set.add(String.valueOf(c));
            }
        }
        return set;
    }

    // 重排用的临时得分载体
    private record ScoredDoc(Document doc, double score) {}
    // cross-encoder 重排用的「下标+分数」载体
    private record IndexedScore(int idx, double score) {}

    /** 阶段5 返回结构：答案 + 引用来源列表 */
    public record RagResult(String answer, List<SourceRef> sources) {}

    /** 单条引用来源 */
    public record SourceRef(String title, String chunkIndex, String snippet) {}

    /**
     * 应用启动后从 PG 恢复 BM25 内存索引（防重启丢失）
     * -------------------------------------------------------------------
     * 向量库是 PG 持久化的，但 BM25 是内存索引（重启即空）。这里在启动事件里
     * 读 chat_document 表的 id/content/metadata.kb 重新灌入 BM25，保证两路数据一致。
     * 表由 @PostConstruct(initVectorStore) 提前建好；若运行在 ingest 之前，表为空，恢复为空属正常。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadBm25FromDb() {
        try {
            jdbcTemplate.query(
                    "SELECT id::text AS id, content, COALESCE(metadata->>'kb','default') AS kb FROM chat_document",
                    (rs, rowNum) -> {
                        bm25.add(rs.getString("id"), rs.getString("content"), rs.getString("kb"));
                        return null;
                    });
            log.info("[BM25] 已从 PG 恢复 {} 篇文档到内存 BM25 索引（重启不丢）", bm25.size());
        } catch (Exception e) {
            // 表不存在 / PG 未连等：不阻断启动，ingest 时会重新灌入
            log.warn("[BM25] 从 PG 恢复索引失败（可忽略，ingest 时会重新灌入）：{}", e.getMessage());
        }
    }

    /**
     * 可选：应用启动后自动灌入演示数据（默认关闭，避免每次启动都烧 embedding 额度）
     * 如需开机自灌，把方法内注释解开并加 @EventListener(ApplicationReadyEvent.class) 即可。
     */
    // @EventListener(ApplicationReadyEvent.class)
    public void autoInitOnReady() {
        initDemo();
    }
}
