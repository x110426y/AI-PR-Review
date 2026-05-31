package org.fourerif.service;

import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.common.IndexParam.IndexType;
import io.milvus.v2.common.IndexParam.MetricType;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq.CollectionSchema;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.data.SparseFloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.SearchResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Milvus 混合检索服务 — 管理 Dense + Sparse 双向量 Collection 的全生命周期。
 * <p>
 * <b>核心职责：</b>
 * <ol>
 *   <li>初始化（或重建）Hybrid Schema Collection：id(PK) + content + dense_vector + sparse_vector</li>
 *   <li>批量插入文档（稠密向量 + 稀疏向量 + 原文）</li>
 *   <li>执行 Hybrid Search（稠密 ANN + 稀疏 ANN → RRF 融合），返回原文</li>
 * </ol>
 * <p>
 * <b>使用 Milvus SDK v2 API：</b>
 * {@link MilvusClientV2} 提供了 {@code hybridSearch()} 方法，
 * 配合 {@link AnnSearchReq}（每路独立检索）和 {@link RRFRanker}（融合排序），
 * 实现与 Python SDK 一致的双路 RRF 混合检索能力。
 * <p>
 * <b>RRF 参数选择：</b>
 * k=60 是标准 RRF 平滑参数，来源于学术界广泛的 RRF 经验研究。
 * k 值越大越强调排名一致性（而非单一分数差异），适合跨异构检索器（Dense + Sparse）的融合场景。
 *
 * @see <a href="https://milvus.io/docs/hybrid_search.md">Milvus Hybrid Search</a>
 * @see <a href="https://dl.acm.org/doi/10.1145/1571941.1572114">RRF Paper (Cormack et al., 2009)</a>
 */
@Service
public class MilvusHybridService {

    private static final Logger log = LoggerFactory.getLogger(MilvusHybridService.class);

    // ==================== 常量 ====================

    public static final String COLLECTION_NAME = "team_conventions";
    static final String DATABASE_NAME = "default";
    static final String FIELD_ID = "id";
    static final String FIELD_CONTENT = "content";
    static final String FIELD_DENSE = "dense_vector";
    static final String FIELD_SPARSE = "sparse_vector";

    /** 稠密向量维度（BGE-Large-ZH-v1.5） */
    static final int DENSE_DIM = 1024;

    /** RRF 平滑参数 k：标准值 60，平衡排名一致性 */
    static final int RRF_K = 60;

    // ==================== 依赖 ====================

    private final MilvusClientV2 milvusClient;
    private final EmbeddingModel embeddingModel;
    private final SparseVectorService sparseVectorService;

    /** 集合是否已就绪（已创建 + 已加载） */
    private volatile boolean ready = false;

    public MilvusHybridService(MilvusClientV2 milvusClient,
                               EmbeddingModel embeddingModel,
                               SparseVectorService sparseVectorService) {
        this.milvusClient = milvusClient;
        this.embeddingModel = embeddingModel;
        this.sparseVectorService = sparseVectorService;
    }

    // ==================== 初始化（由 RagConfig 在启动时调用） ====================

    /**
     * 初始化 Hybrid Collection：先删后建，确保 Schema 干净。
     * <p>
     * <b>执行顺序：</b>
     * <ol>
     *   <li>检查集合是否存在 → 存在则 Drop</li>
     *   <li>创建包含 4 个字段的 Collection（id, content, dense_vector, sparse_vector）</li>
     *   <li>分别为 dense_vector (AUTOINDEX+COSINE) 和 sparse_vector (SPARSE_INVERTED_INDEX+IP) 建索引</li>
     *   <li>加载 Collection 到内存</li>
     * </ol>
     *
     * @return 是否创建成功
     */
    public boolean initCollection() {
        try {
            // --- Step 1: 清理旧表 ---
            if (milvusClient.hasCollection(HasCollectionReq.builder()
                    .collectionName(COLLECTION_NAME).build())) {
                log.info("检测到旧 Collection '{}'，正在删除...", COLLECTION_NAME);
                milvusClient.dropCollection(DropCollectionReq.builder()
                        .collectionName(COLLECTION_NAME).build());
                // 等待删除完成（异步操作需要短暂等待）
                Thread.sleep(1000);
                log.info("旧 Collection 已删除");
            }

            // --- Step 2: 创建 Hybrid Schema ---
            log.info("创建 Hybrid Collection '{}' (Dense={}d + Sparse)...", COLLECTION_NAME, DENSE_DIM);

            CollectionSchema schema = CollectionSchema.builder()
                    .enableDynamicField(true)
                    .build()
                    // 主键：自增 Int64
                    .addField(AddFieldReq.builder()
                            .fieldName(FIELD_ID)
                            .dataType(DataType.Int64)
                            .isPrimaryKey(true)
                            .autoID(true)
                            .build())
                    // 文本内容
                    .addField(AddFieldReq.builder()
                            .fieldName(FIELD_CONTENT)
                            .dataType(DataType.VarChar)
                            .maxLength(65535)
                            .build())
                    // 稠密向量：1024 维 FloatVector
                    .addField(AddFieldReq.builder()
                            .fieldName(FIELD_DENSE)
                            .dataType(DataType.FloatVector)
                            .dimension(DENSE_DIM)
                            .build())
                    // 稀疏向量：SparseFloatVector（维度上限由词表大小确定）
                    .addField(AddFieldReq.builder()
                            .fieldName(FIELD_SPARSE)
                            .dataType(DataType.SparseFloatVector)
                            .build());

            milvusClient.createCollection(CreateCollectionReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .collectionSchema(schema)
                    .build());
            log.info("Collection '{}' 创建成功", COLLECTION_NAME);

            // --- Step 3: 创建索引 ---
            // Dense 索引：AUTOINDEX + COSINE（让 Milvus 自动选择最优索引）
            IndexParam denseIndex = IndexParam.builder()
                    .fieldName(FIELD_DENSE)
                    .indexType(IndexType.AUTOINDEX)
                    .metricType(MetricType.COSINE)
                    .build();

            // Sparse 索引：SPARSE_INVERTED_INDEX + IP（内积最适合稀疏向量）
            IndexParam sparseIndex = IndexParam.builder()
                    .fieldName(FIELD_SPARSE)
                    .indexType(IndexType.SPARSE_INVERTED_INDEX)
                    .metricType(MetricType.IP)
                    .build();

            milvusClient.createIndex(CreateIndexReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .indexParams(List.of(denseIndex, sparseIndex))
                    .build());
            log.info("双索引创建成功: Dense=AUTOINDEX/COSINE, Sparse=SPARSE_INVERTED/IP");

            // --- Step 4: 加载 Collection ---
            milvusClient.loadCollection(LoadCollectionReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .build());
            log.info("Collection '{}' 已加载到内存", COLLECTION_NAME);

            ready = true;
            return true;

        } catch (Exception e) {
            log.error("初始化 Hybrid Collection 失败: {}", e.getMessage(), e);
            return false;
        }
    }

    // ==================== 文档插入 ====================

    /**
     * 批量插入文档及其双向量（Dense + Sparse）。
     * <p>
     * <b>调用时机：</b> 应用启动时，RagConfig 加载团队规范片段后批量调用。
     * <p>
     * <b>注意：</b> Embedding 和 BM25 索引均在内存中完成，
     * 本方法仅负责将已计算好的向量写入 Milvus。
     *
     * @param documents    文本片段列表（TokenTextSplitter 切分后的 Document 对象）
     * @param sparseVectors 每个文档对应的稀疏向量，由 SparseVectorService.indexDocument() 生成
     * @return 实际插入的行数
     */
    public int insertDocuments(List<Document> documents, List<Map<Integer, Float>> sparseVectors) {
        if (documents.isEmpty()) {
            log.warn("文档列表为空，跳过插入");
            return 0;
        }

        try {
            long startTime = System.currentTimeMillis();
            List<JsonObject> rows = new ArrayList<>(documents.size());

            for (int i = 0; i < documents.size(); i++) {
                Document doc = documents.get(i);
                String text = doc.getText();
                Map<Integer, Float> sparseVec = i < sparseVectors.size() ? sparseVectors.get(i) : Map.of();

                // Dense Embedding → float[] (Spring AI 1.0.0 API)
                float[] denseArray = embeddingModel.embed(text);
                List<Float> denseList = new ArrayList<>(denseArray.length);
                for (float f : denseArray) {
                    denseList.add(f);
                }

                // 转 JsonObject 格式插入
                JsonObject row = new JsonObject();
                row.addProperty(FIELD_CONTENT, text);

                // Dense vector → JSON array
                row.add(FIELD_DENSE, floatsToJson(denseList));

                // Sparse vector → JSON object {dimIndex: value, ...}
                JsonObject sparseJson = new JsonObject();
                for (Map.Entry<Integer, Float> entry : sparseVec.entrySet()) {
                    sparseJson.addProperty(String.valueOf(entry.getKey()), entry.getValue());
                }
                row.add(FIELD_SPARSE, sparseJson);

                rows.add(row);
            }

            InsertResp resp = milvusClient.insert(InsertReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .data(rows)
                    .build());

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("批量插入完成: {} 条文档, 耗时 {} ms, 实际写入 {} 行",
                    documents.size(), elapsed, resp.getInsertCnt());
            return Math.toIntExact(resp.getInsertCnt());

        } catch (Exception e) {
            log.error("文档批量插入失败: {}", e.getMessage(), e);
            return 0;
        }
    }

    // ==================== 混合检索（核心） ====================

    /**
     * 执行 Dense + Sparse 双路 Hybrid Search，RRF 融合排序。
     * <p>
     * <b>检索流程：</b>
     * <ol>
     *   <li>对查询文本生成 Dense Embedding（1024 维浮点向量）</li>
     *   <li>对查询文本生成 Sparse 向量（BM25 TF×IDF 权重）</li>
     *   <li>构建两路 {@link AnnSearchReq}，分别检索 {@code dense_vector} 和 {@code sparse_vector} 字段</li>
     *   <li>{@link HybridSearchReq} 将两路结果交由 {@link RRFRanker}(k=60) 融合</li>
     *   <li>提取每个结果的 {@code content} 字段返回</li>
     * </ol>
     * <p>
     * <b>为什么 RRF 而非加权求和？</b>
     * Dense 打分（COSINE 0~1）和 Sparse 打分（IP, 无上界）的数值尺度差异极大，
     * 硬加权需要对两种分数的分布做归一化处理，超参敏感。
     * RRF 仅依赖排名位置，天然跨异构检索器可比，开箱即用无调参。
     *
     * @param queryText 查询文本（Diff Chunk 前 500 字符）
     * @param topK      最终返回结果数
     * @return 检索到的文档文本列表；若检索失败返回空列表
     */
    public List<String> hybridSearch(String queryText, int topK) {
        if (!ready) {
            log.warn("Hybrid Collection 尚未就绪，跳过检索");
            return List.of();
        }

        try {
            long startTime = System.currentTimeMillis();

            // 1. 生成查询双向量 → float[] (Spring AI 1.0.0 API)
            float[] denseArray = embeddingModel.embed(queryText);
            List<Float> denseList = new ArrayList<>(denseArray.length);
            for (float f : denseArray) {
                denseList.add(f);
            }

            Map<Integer, Float> sparseMap = sparseVectorService.encodeQuery(queryText);

            if (sparseMap.isEmpty()) {
                log.debug("查询无命中词表 bigram，Sparse 路退化为空；仅执行 Dense 检索");
            }

            // 2. 构建 Dense 检索子请求
            AnnSearchReq denseReq = AnnSearchReq.builder()
                    .vectorFieldName(FIELD_DENSE)
                    .vectors(List.of(new FloatVec(denseList)))
                    .metricType(MetricType.COSINE)
                    .topK(topK * 2)          // 每路召回 2×topK 以保证融合后多样性
                    .params("{\"nprobe\": 10}")
                    .build();

            // 3. 构建 Sparse 检索子请求
            // SparseFloatVec 要求 SortedMap<Long, Float>（Long = 维度索引, Float = 权重）
            SortedMap<Long, Float> sparseSorted = new TreeMap<>();
            if (sparseMap.isEmpty()) {
                // 稀疏向量不可为空，填入一个零权重大哑元维度
                // 此维度不在任何文档中出现，内积为 0，对 RRF 排名无影响
                sparseSorted.put(0L, 0.0f);
            } else {
                for (Map.Entry<Integer, Float> entry : sparseMap.entrySet()) {
                    sparseSorted.put(entry.getKey().longValue(), entry.getValue());
                }
            }

            AnnSearchReq sparseReq = AnnSearchReq.builder()
                    .vectorFieldName(FIELD_SPARSE)
                    .vectors(List.of(new SparseFloatVec(sparseSorted)))
                    .metricType(MetricType.IP)       // Sparse Float 用 IP (Inner Product)
                    .topK(topK * 2)
                    .params("{\"drop_ratio_search\": 0.2}")
                    .build();

            // 4. Hybrid Search + RRF 融合
            List<AnnSearchReq> searchRequests = new ArrayList<>();
            searchRequests.add(denseReq);
            searchRequests.add(sparseReq);

            HybridSearchReq hybridReq = HybridSearchReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .searchRequests(searchRequests)
                    .ranker(new RRFRanker(RRF_K))
                    .topK(topK)
                    .outFields(List.of(FIELD_CONTENT))   // 仅返回文本内容字段
                    .build();

            SearchResp resp = milvusClient.hybridSearch(hybridReq);

            long elapsed = System.currentTimeMillis() - startTime;

            // 5. 提取结果
            List<String> results = new ArrayList<>();
            if (resp.getSearchResults() != null) {
                for (List<SearchResp.SearchResult> batch : resp.getSearchResults()) {
                    for (SearchResp.SearchResult sr : batch) {
                        if (sr.getEntity() != null && sr.getEntity().containsKey(FIELD_CONTENT)) {
                            results.add(String.valueOf(sr.getEntity().get(FIELD_CONTENT)));
                        }
                    }
                }
            }

            log.info("Hybrid Search 完成: query={} chars, denseOK, sparseHits={}, "
                            + "RRF k={}, topK={}, 结果数={}, 耗时 {} ms",
                    queryText.length(),
                    sparseMap.size(),
                    RRF_K, topK, results.size(), elapsed);
            return results;

        } catch (Exception e) {
            log.error("Hybrid Search 执行失败: {}", e.getMessage(), e);
            return List.of();
        }
    }

    // ==================== 辅助方法 ====================

    /** 将 List&lt;Float&gt; 转为 Gson JsonArray */
    private static com.google.gson.JsonArray floatsToJson(List<Float> floats) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (Float f : floats) {
            arr.add(f);
        }
        return arr;
    }
}
