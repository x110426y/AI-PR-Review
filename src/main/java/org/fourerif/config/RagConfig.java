package org.fourerif.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * RAG（检索增强生成）配置 —— 本地 Milvus Standalone 版。
 * <p>
 * 手动创建 {@link MilvusServiceClient} 和 {@link MilvusVectorStore} Bean，
 * 绕过 Spring AI 自动配置的属性命名空间差异。
 * 本地 Docker 部署无需认证 token，连接地址固定为 localhost:19530。
 * <p>
 * 启动时自动加载团队编码规范文档，切分为语义片段后写入 Milvus 向量库。
 */
@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    // ==================== Milvus 连接参数（本地部署，无需 token）====================

    @Value("${spring.vectorstore.milvus.uri:http://localhost:19530}")
    private String milvusUri;

    // ==================== Bean 定义 ====================

    /**
     * 创建 MilvusServiceClient，连接本地 Docker Milvus Standalone。
     * 本地部署无需 token 认证，仅需 gRPC 地址。
     */
    @Bean
    public MilvusServiceClient milvusServiceClient() {
        log.info("正在连接本地 Milvus Standalone: {}", milvusUri);

        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withUri(milvusUri)
                .withConnectTimeout(15, TimeUnit.SECONDS)
                .withIdleTimeout(60, TimeUnit.SECONDS);

        MilvusServiceClient client = new MilvusServiceClient(builder.build());
        log.info("MilvusServiceClient 已就绪（本地 Standalone，无认证）");
        return client;
    }

    /**
     * 创建 MilvusVectorStore。
     * 依赖 {@code initializeSchema=true} 让 Spring AI 在启动时自动建表。
     * 本地环境无集合数量限制，无需手动干预。
     */
    @Bean
    public VectorStore vectorStore(MilvusServiceClient milvusClient, EmbeddingModel embeddingModel) {
        log.info("初始化 MilvusVectorStore (Collection: team_conventions, Dim: 1024)");
        return MilvusVectorStore.builder(milvusClient, embeddingModel)
                .collectionName("team_conventions")
                .databaseName("default")
                .embeddingDimension(1024)
                .initializeSchema(true)
                .build();
    }

    // ==================== 数据加载 ====================

    /**
     * 应用启动时加载团队规范文件，切分后写入 Milvus。
     */
    @Bean
    ApplicationRunner initVectorStore(VectorStore vectorStore,
                                       @Value("classpath:rules/team-conventions.md") Resource conventionsResource) {
        return args -> {
            log.info("正在加载团队规范文件...");
            String content = conventionsResource.getContentAsString(StandardCharsets.UTF_8);

            TokenTextSplitter splitter = new TokenTextSplitter(
                    300, 50, 20, 50, true);

            Document sourceDoc = new Document(content,
                    Map.of("source", "team-conventions.md", "type", "coding-standard"));
            List<Document> chunks = splitter.apply(List.of(sourceDoc));

            vectorStore.add(chunks);

            log.info("团队规范加载完成: 原始 {} 字符 → {} 个文档片段已写入 Milvus",
                    content.length(), chunks.size());
        };
    }
}
