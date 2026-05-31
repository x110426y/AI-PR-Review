package org.fourerif.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.fourerif.service.MilvusHybridService;
import org.fourerif.service.SparseVectorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * RAG 配置 — Hybrid Search (Dense + Sparse) + RRF 版。
 * <p>
 * 与旧版的区别：
 * <ol>
 *   <li>不再使用 Spring AI 的 {@code MilvusVectorStore}（仅支持单稠密向量）</li>
 *   <li>改用自定义 {@link MilvusHybridService} — 手动管理包含 Dense + Sparse 双向量字段的 Collection</li>
 *   <li>启动时自动删除旧表、创建全新 Hybrid Schema、导入团队规范</li>
 * </ol>
 * <p>
 * <b>双客户端策略：</b>
 * {@link MilvusServiceClient} (v1 API) 用于连接管理；
 * {@link MilvusClientV2} (v2 API) 用于 Hybrid Search 和 Schema 操作。
 */
@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    // ==================== Milvus 连接参数 ====================

    @Value("${spring.vectorstore.milvus.uri:http://localhost:19530}")
    private String milvusUri;

    @Value("${spring.vectorstore.milvus.database-name:default}")
    private String databaseName;

    // ==================== Bean 定义 ====================

    /**
     * 创建 Milvus v1 客户端（保留兼容性，未来可移除）。
     */
    @Bean
    public MilvusServiceClient milvusServiceClient() {
        log.info("正在连接本地 Milvus Standalone: {}", milvusUri);

        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withUri(milvusUri)
                .withConnectTimeout(15, TimeUnit.SECONDS)
                .withIdleTimeout(60, TimeUnit.SECONDS);

        MilvusServiceClient client = new MilvusServiceClient(builder.build());
        log.info("MilvusServiceClient (v1) 已就绪");
        return client;
    }

    /**
     * 创建 Milvus v2 客户端 — 支持 Hybrid Search 原生 API。
     * <p>
     * {@link MilvusClientV2} 提供了 {@code hybridSearch()} 方法，
     * 这是实现 Dense + Sparse + RRF 混合检索的核心入口。
     */
    @Bean
    public MilvusClientV2 milvusClientV2() {
        log.info("正在创建 MilvusClientV2 (支持 Hybrid Search): {}", milvusUri);

        ConnectConfig config = ConnectConfig.builder()
                .uri(milvusUri)
                .dbName(databaseName)
                .build();

        MilvusClientV2 client = new MilvusClientV2(config);
        log.info("MilvusClientV2 已就绪 (Database: {})", databaseName);
        return client;
    }

    // ==================== 数据加载 ====================

    /**
     * 应用启动时：
     * <ol>
     *   <li>调用 {@link MilvusHybridService#initCollection()} — 删旧建新</li>
     *   <li>加载团队规范文件，TokenTextSplitter 切分</li>
     *   <li>逐条建立 BM25 索引（{@link SparseVectorService#indexDocument}）</li>
     *   <li>批量插入：Dense Embedding + Sparse Vector + 原文 → Milvus</li>
     * </ol>
     */
    @Bean
    ApplicationRunner initVectorStore(MilvusHybridService hybridService,
                                       SparseVectorService sparseService,
                                       EmbeddingModel embeddingModel,
                                       @Value("classpath:rules/team-conventions.md") Resource conventionsResource) {
        return args -> {
            // Step 1: 初始化 Hybrid Collection (删旧 + 建新 + 建索引 + 加载)
            log.info("=== 开始初始化 Hybrid RAG 系统 ===");
            if (!hybridService.initCollection()) {
                log.error("Hybrid Collection 初始化失败，RAG 将不可用");
                return;
            }

            // Step 2: 加载 + 切分团队规范
            log.info("正在加载团队规范文件...");
            String content = conventionsResource.getContentAsString(StandardCharsets.UTF_8);

            TokenTextSplitter splitter = new TokenTextSplitter(
                    300, 50, 20, 50, true);

            Document sourceDoc = new Document(content,
                    Map.of("source", "team-conventions.md", "type", "coding-standard"));
            List<Document> chunks = splitter.apply(List.of(sourceDoc));
            log.info("团队规范切分完成: {} 字符 → {} 个语义片段", content.length(), chunks.size());

            // Step 3: 逐条建 BM25 索引 + 收集 Sparse 向量
            List<Map<Integer, Float>> sparseVectors = new ArrayList<>();
            for (Document chunk : chunks) {
                Map<Integer, Float> sparseVec = sparseService.indexDocument(chunk.getText());
                sparseVectors.add(sparseVec);
            }

            // Step 4: 批量插入 (Dense + Sparse + Text)
            int inserted = hybridService.insertDocuments(chunks, sparseVectors);

            log.info("=== Hybrid RAG 系统初始化完成 ===");
            log.info("  集合: {}", MilvusHybridService.COLLECTION_NAME);
            log.info("  文档: {} 个语义片段已入库", inserted);
            log.info("  Dense: 1024d (BGE-Large-ZH-v1.5, AUTOINDEX + COSINE)");
            log.info("  Sparse: {} bigrams (Local BM25 + Character Bigram, SPARSE_INVERTED_INDEX + IP)",
                    sparseService.getVocabSize());
            log.info("  检索: Hybrid Search + RRF (k=60)");
        };
    }
}
