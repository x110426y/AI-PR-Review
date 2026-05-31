package org.fourerif.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * RAG (检索增强生成) 配置。
 * <p>
 * 使用 Spring AI 内存向量库 {@link SimpleVectorStore} 存储团队代码规范，
 * 在 AI 审查时通过相似度检索将最相关的规范注入 Prompt，使审查结果贴合团队实际标准。
 * <p>
 * <b>为什么选择 SimpleVectorStore？</b>
 * MVP 阶段无需引入 Redis / Milvus / pgvector 等外部向量数据库。
 * {@code SimpleVectorStore} 将所有向量数据保存在 JVM 堆内存中，
 * 对于团队规范这种小规模、低频更新场景完全足够。
 * 未来规模增长后可无缝切换至 {@code PgVectorStore} 或 {@code RedisVectorStore}。
 */
@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    /**
     * 创建内存向量库 Bean（Builder 模式，Spring AI 1.0.0-M5+ 推荐写法）。
     * <p>
     * {@link EmbeddingModel} 由 {@code spring-ai-openai-spring-boot-starter} 自动配置，
     * 使用 {@code application.yml} 中配置的 Embedding 模型。
     */
    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        log.info("初始化 SimpleVectorStore (内存向量库)，Embedding 模型: {}",
                embeddingModel.getClass().getSimpleName());
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    /**
     * 应用启动时加载团队规范文件，切分为文档片段后写入向量库。
     * <p>
     * 切分策略：使用 {@link TokenTextSplitter} 按语义边界将 Markdown 规范文件
     * 切分为多个独立的 Document。每个规范条目成为一个独立的检索单元，
     * 确保相似度检索时能精确定位到最相关的规则。
     */
    @Bean
    ApplicationRunner initVectorStore(VectorStore vectorStore,
                                       @Value("classpath:rules/team-conventions.md") Resource conventionsResource) {
        return args -> {
            log.info("正在加载团队规范文件...");
            String content = conventionsResource.getContentAsString(StandardCharsets.UTF_8);

            // 使用 TokenTextSplitter 切分文档（按语义段落，每个 Chunk ≈ 300 tokens）
            TokenTextSplitter splitter = new TokenTextSplitter(
                    300,    // defaultChunkSize: 每个文档片段约 300 tokens（约 200-300 中文字符）
                    50,     // minChunkSizeChars: 不创建小于 50 字符的片段
                    20,     // minChunkLengthToEmbed: 片段至少 20 字符才入库
                    50,     // maxNumChunks: 最多切分为 50 个片段
                    true    // keepSeparator: 保留分隔符保持上下文完整
            );

            Document sourceDoc = new Document(content,
                    Map.of("source", "team-conventions.md", "type", "coding-standard"));
            List<Document> chunks = splitter.apply(List.of(sourceDoc));

            // 写入向量库（自动 Embedding + 索引）
            vectorStore.add(chunks);

            log.info("团队规范加载完成: 原始 {} 字符 → {} 个文档片段已向量化入库",
                    content.length(), chunks.size());
        };
    }
}
