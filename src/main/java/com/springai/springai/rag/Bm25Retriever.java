package com.springai.springai.rag;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 纯 Java 实现的 Okapi BM25 检索（不依赖 Spring AI，便于单测/移植）。
 * -------------------------------------------------------------------
 * 企业里 BM25 通常由 Elasticsearch/Lucene 或 Postgres tsvector 提供，
 * 这里实现的是其底层算法核心，方便你从 0 看懂并在项目里直接跑。
 *
 * 公式（Okapi BM25）：
 *   score(D,Q) = Σ_{t∈Q} IDF(t) · ( tf(t,D)·(k1+1) ) / ( tf(t,D) + k1·(1−b+b·|D|/avgdl) )
 *   IDF(t)     = ln( (N − df(t) + 0.5) / (df(t) + 0.5) + 1 )
 *   k1=1.5 控制词频饱和度（出现越多分越高但边际递减），b=0.75 控制文档长度归一化。
 */
public class Bm25Retriever {

    private final List<Entry> docs = new ArrayList<>();
    private final Map<String, Integer> df = new HashMap<>();   // 词项 -> 出现它的文档数（doc frequency）
    private int totalLen = 0;                                   // 所有文档词项总数（算平均长度用）
    private final double k1;
    private final double b;

    public Bm25Retriever() { this(1.5, 0.75); }
    public Bm25Retriever(double k1, double b) { this.k1 = k1; this.b = b; }

    /** 分词：中文按单字、英文/数字按完整词（索引侧与查询侧必须用同一套，否则对不上） */
    public static Set<String> tokenize(String s) {
        Set<String> set = new HashSet<>();
        if (s == null) return set;
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c > 0x4E00) set.add(String.valueOf(c));
        }
        return set;
    }

    /** 往索引里加一篇文档（id 与向量库同一批文档的 id 保持一致，融合时才能按 id 去重） */
    public synchronized void add(String id, String text, String kb) {
        Set<String> terms = tokenize(text);
        List<String> termList = new ArrayList<>(terms);
        docs.add(new Entry(id, text, kb, termList, termList.size()));
        for (String t : terms) {
            Integer cur = df.get(t);
            df.put(t, cur == null ? 1 : cur + 1);
        }
        totalLen += termList.size();
    }

    /** 查询：返回指定 kb 内、按 BM25 分数降序的前 topK 篇 */
    public synchronized List<Hit> retrieve(String query, String kb, int topK) {
        if (docs.isEmpty()) return Collections.emptyList();
        Set<String> qTerms = tokenize(query);
        if (qTerms.isEmpty()) return Collections.emptyList();

        double avgdl = (double) totalLen / docs.size();   // 平均文档长度（词项数）
        int N = docs.size();                              // 文档总数

        List<Scored> scored = new ArrayList<>();
        for (Entry e : docs) {
            if (!e.kb.equals(kb)) continue;               // 知识库隔离，和向量路一致
            // 统计本篇词频 tf
            Map<String, Integer> tf = new HashMap<>();
            for (String t : e.terms) {
                Integer cur = tf.get(t);
                tf.put(t, cur == null ? 1 : cur + 1);
            }
            double score = 0.0;
            for (String t : qTerms) {
                Integer dft = df.get(t);
                if (dft == null) continue;                // 查询词从没出现过，IDF 视为 0
                Integer f = tf.get(t);
                if (f == null) continue;                  // 本篇没这个词
                double idf = Math.log((N - dft + 0.5) / (dft + 0.5) + 1.0);
                double denom = f + k1 * (1 - b + b * ((double) e.len / avgdl));
                score += idf * (f * (k1 + 1)) / denom;
            }
            if (score > 0) scored.add(new Scored(e.id, e.text, e.kb, score));
        }
        Collections.sort(scored, new Comparator<Scored>() {
            public int compare(Scored a, Scored b) { return Double.compare(b.score, a.score); }
        });
        List<Hit> hits = new ArrayList<>();
        int lim = Math.min(topK, scored.size());
        for (int i = 0; i < lim; i++) {
            Scored s = scored.get(i);
            hits.add(new Hit(s.id, s.text, s.kb, s.score));
        }
        return hits;
    }

    public int size() { return docs.size(); }

    // ===================== 内部数据结构 =====================
    private static class Entry {
        String id, text, kb;
        List<String> terms;
        int len;
        Entry(String id, String text, String kb, List<String> terms, int len) {
            this.id = id; this.text = text; this.kb = kb; this.terms = terms; this.len = len;
        }
    }

    private static class Scored {
        String id, text, kb;
        double score;
        Scored(String id, String text, String kb, double score) {
            this.id = id; this.text = text; this.kb = kb; this.score = score;
        }
    }

    /** 对外返回的检索命中（id 用于回查原文档，score 用于调试/排序） */
    public static class Hit {
        public final String id, text, kb;
        public final double score;
        public Hit(String id, String text, String kb, double score) {
            this.id = id; this.text = text; this.kb = kb; this.score = score;
        }
    }
}
