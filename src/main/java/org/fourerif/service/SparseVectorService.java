package org.fourerif.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地 BM25 稀疏向量服务 — 字符级 Bigram 分词。
 * <p>
 * <b>为什么选择纯 Java 字符 Bigram + BM25？</b>
 * <ul>
 *   <li><b>零依赖</b>：无需引入 jieba/HanLP 等分词库（~30MB），避免 classpath 膨胀</li>
 *   <li><b>中文友好</b>：字符级 bigram 对中文天然友好 — "禁止SQL注入"
 *       拆分为 {"禁止","止S","SQ","QL","L注","注入"}，覆盖单字和双字组合</li>
 *   <li><b>精确匹配</b>：BM25 统计方法擅长匹配领域特定术语（如 "Controller"、"DAO"、"SQL注入"），
 *       恰好弥补 Dense Embedding 在精确关键词匹配上的短板</li>
 *   <li><b>确定性</b>：相同输入永远输出相同向量，无 Embedding 模型噪声</li>
 * </ul>
 * <p>
 * <b>架构角色：</b>
 * 本服务在应用启动时对团队规范文档建立 BM25 索引（构建词表 + 计算文档 TF），
 * 每次 Hybrid Search 时为查询文本生成稀疏向量（BM25 权重），
 * 配合 Milvus 的 SparseFloatVector 字段和 Dense Embedding 字段进行双路 RRF 融合检索。
 * <p>
 * <b>SparseFloatVector 协议：</b>
 * Milvus 稀疏向量采用稀疏格式存储：每个维度索引对应一个非零浮点值。
 * 维度总数 = 词表大小（所有文档中出现的唯一 bigram 数），每个文档仅在其包含的 bigram 维度上有非零值。
 * <ul>
 *   <li>文档侧存储：归一化 TF (term frequency / doc_length)</li>
 *   <li>查询侧存储：BM25 权重 = TF_q * IDF (Inverse Document Frequency)</li>
 *   <li>Milvus 内部计算：IP (Inner Product) 内积作为稀疏相似度</li>
 * </ul>
 *
 * @see <a href="https://en.wikipedia.org/wiki/Okapi_BM25">Okapi BM25</a>
 */
@Service
public class SparseVectorService {

    private static final Logger log = LoggerFactory.getLogger(SparseVectorService.class);

    /** BM25 参数：词频饱和控制，值越小越抑制高频词 */
    private static final double K1 = 1.5;

    /** BM25 参数：文档长度归一化强度，值越小文档长度影响越小 */
    private static final double B = 0.75;

    // ==================== 词表索引 ====================

    /** bigram → 维度索引（稀疏向量中的列号） */
    private final Map<String, Integer> vocab = new HashMap<>();

    /** 维度索引 → bigram（仅用于调试日志） */
    private final List<String> reverseVocab = new ArrayList<>();

    // ==================== BM25 索引统计 ====================

    /** 每个文档的 bigram 频次列表 (docId → [bigramIdx → tf_raw]) */
    private final List<Map<Integer, Integer>> docTermFreqs = new ArrayList<>();

    /** 文档总数 */
    private int docCount = 0;

    /** 每个 bigram 在多少个文档中出现过 (doc frequency) */
    private final Map<Integer, Integer> docFreq = new HashMap<>();

    /** 所有文档的平均长度（字符数） */
    private double avgDocLength = 0;

    // ==================== 公共 API ====================

    /**
     * 索引一篇文档到 BM25 词表。
     * <p>
     * 调用时机：应用启动时，RagConfig 加载团队规范并逐条调用。
     * 此方法同时完成：
     * <ol>
     *   <li>字符 bigram 分词</li>
     *   <li>更新全局词表（新 bigram 分配维度索引）</li>
     *   <li>计算该文档的 TF 统计</li>
     *   <li>更新 DF 和平均文档长度</li>
     * </ol>
     *
     * @param text 文档原始文本（团队规范片段）
     * @return 该文档的稀疏向量表示 {(维度索引: 归一化 TF)}
     */
    public Map<Integer, Float> indexDocument(String text) {
        // 1. 字符 bigram 分词
        String tokenStr = preprocess(text);
        List<String> bigrams = charBigram(tokenStr);

        // 2. 统计该文档中各 bigram 的出现次数
        Map<Integer, Integer> tfRaw = new HashMap<>();
        for (String bg : bigrams) {
            int idx = vocab.computeIfAbsent(bg, k -> {
                reverseVocab.add(k);
                return reverseVocab.size() - 1;
            });
            tfRaw.merge(idx, 1, Integer::sum);
        }

        // 3. 计算文档长度（bigram 总数）和归一化 TF
        int docLen = bigrams.size();
        Map<Integer, Float> sparseVector = new HashMap<>();
        for (Map.Entry<Integer, Integer> entry : tfRaw.entrySet()) {
            int idx = entry.getKey();
            int tf = entry.getValue();
            // 归一化 TF = tf / docLen (防止长文档在 IP 计算中占优)
            sparseVector.put(idx, (float) tf / docLen);

            // 更新文档频率 (DF)
            docFreq.merge(idx, 1, Integer::sum);
        }

        // 4. 更新全局统计
        docTermFreqs.add(tfRaw);
        docCount++;
        avgDocLength = avgDocLength + (docLen - avgDocLength) / docCount; // 增量平均

        if (log.isDebugEnabled()) {
            log.debug("BM25 索引文档 #{}: {} 字符 → {} bigrams → {} 唯一 bigram",
                    docCount, text.length(), docLen, tfRaw.size());
        }

        return sparseVector;
    }

    /**
     * 为查询文本生成 BM25 稀疏向量。
     * <p>
     * 调用时机：每次 RAG 检索时，为 Diff Chunk 构造查询向量。
     * <p>
     * <b>公式：</b>
     * <pre>
     *   score(bigram, query) = tf_q × IDF
     *   IDF = log((N - df + 0.5) / (df + 0.5) + 1)
     * </pre>
     * N = 文档总数, df = 包含该 bigram 的文档数, tf_q = 查询中该 bigram 的原始频次
     * <p>
     * 查询侧不使用 K1/B 平滑，直接使用原始 TF×IDF 的稀疏向量，
     * 由 Milvus 在内部执行 IP (Inner Product) 与文档向量计算相似度。
     *
     * @param queryText 查询文本（Diff Chunk 前 N 个字符）
     * @return 查询的稀疏向量 {(维度索引: TF×IDF 权重)}
     */
    public Map<Integer, Float> encodeQuery(String queryText) {
        if (vocab.isEmpty()) {
            log.warn("BM25 词表为空（可能尚无文档索引），返回空稀疏向量");
            return Collections.emptyMap();
        }

        String tokenStr = preprocess(queryText);
        List<String> bigrams = charBigram(tokenStr);

        // 统计查询中的 bigram 频次
        Map<Integer, Integer> queryTF = new HashMap<>();
        for (String bg : bigrams) {
            Integer idx = vocab.get(bg);     // 仅使用已知词表，OOV bigram 丢弃
            if (idx != null) {
                queryTF.merge(idx, 1, Integer::sum);
            }
        }

        // BM25 查询权重 = TF_q × IDF (Robertson-Sparck Jones IDF 变体)
        Map<Integer, Float> querySparse = new HashMap<>();
        for (Map.Entry<Integer, Integer> entry : queryTF.entrySet()) {
            int idx = entry.getKey();
            int tf = entry.getValue();
            int df = docFreq.getOrDefault(idx, 0);

            // Smooth IDF: 防止 df=0 时的除零
            double idf = Math.log((docCount - df + 0.5) / (df + 0.5) + 1.0);
            float weight = (float) (tf * idf);

            querySparse.put(idx, weight);
        }

        if (log.isDebugEnabled()) {
            log.debug("BM25 查询编码: {} 字符 → {} bigrams → {} 命中词表 (词表大小: {})",
                    queryText.length(), bigrams.size(), querySparse.size(), vocab.size());
        }

        return querySparse;
    }

    /**
     * 获取完整稀疏向量维度（词表大小）。
     * <p>
     * Milvus SparseFloatVector 需要预定义最大维度。
     *
     * @return 词表中唯一 bigram 的数量
     */
    public int getVocabSize() {
        return vocab.size();
    }

    /**
     * 获取 BM25 索引统计摘要，用于启动日志。
     */
    public String getStats() {
        if (docCount == 0) {
            return "docCount=0, vocabSize=0";
        }
        return String.format("docCount=%d, vocabSize=%d, avgDocLen=%.1f chars",
                docCount, vocab.size(), avgDocLength);
    }

    // ==================== 分词 ====================

    /**
     * 预处理：去除空白 + 统一大小写 + 保留中英文/数字/符号。
     * <p>
     * 对代码 Diff 和规范文本均适用：保留关键字符如 {@code @ # $ { } ( ) . /}
     * 确保 "Controller"、"@Service"、"${}" 等 Java 特有符号被正确编码。
     */
    private String preprocess(String text) {
        if (text == null || text.isBlank()) return "";
        // 规范化空白（多个空格 → 单个）
        return text.strip().replaceAll("\\s+", " ");
    }

    /**
     * 字符级 Bigram 分词。
     * <p>
     * <b>示例：</b>
     * <pre>
     *   "禁止SQL注入" →
     *   ["禁止", "止S", "SQ", "QL", "L注", "注入"]
     * </pre>
     * <p>
     * <b>对短文本的保护：</b>
     * 单字符文本在 bigram 视角下无相邻字符可组，为保证其可检索，
     * 回退为包含该字符自身的单字符 "gram"。
     * <p>
     * <b>为什么不用词级分词？</b>
     * 代码 Diff 和规范术语中大量混合中英文（如 "Controller禁止"），
     * 词级分词（jieba/HanLP）可能将混合文本错误切分。
     * 字符 bigram 的粒度假定"相邻字符经常共现"即可捕捉词汇边界，
     * 且对拼写错误、缩写具有天然鲁棒性。
     *
     * @param text 预处理后的文本
     * @return bigram 列表
     */
    private List<String> charBigram(String text) {
        if (text.length() <= 1) {
            return text.isEmpty() ? Collections.emptyList() : List.of(text);
        }

        List<String> result = new ArrayList<>(text.length() - 1);
        for (int i = 0; i < text.length() - 1; i++) {
            result.add(text.substring(i, i + 2));
        }
        return result;
    }
}