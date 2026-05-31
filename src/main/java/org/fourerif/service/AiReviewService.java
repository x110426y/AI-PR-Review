package org.fourerif.service;

import org.fourerif.dto.ReviewResult;
import org.fourerif.dto.RiskItem;
import org.fourerif.dto.SuggestionItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * AI Review 服务 — 核心业务逻辑层。
 * <p>
 * 使用 Spring AI 的 {@link ChatClient} 调用大模型进行代码审查，
 * 通过 {@link BeanOutputConverter} 强制模型输出结构化 JSON，
 * 并映射到 {@link ReviewResult} 实体。
 * <p>
 * Prompt 模板独立存放在 {@code classpath:prompts/review-prompt.st}，
 * 运行时动态填充 PR 数据后发送给大模型。
 */
@Service
public class AiReviewService {

    private static final Logger log = LoggerFactory.getLogger(AiReviewService.class);

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final String promptTemplate;

    /** 检索团队规范时的 Top-K 参数 — 返回最相关的 K 条规范 */
    private static final int TOP_K_CONVENTIONS = 3;

    /** 检索查询词的最大长度（取 Diff Chunk 前 N 个字符），避免过长查询稀释语义 */
    private static final int QUERY_MAX_LENGTH = 500;

    /**
     * 构造函数注入 ChatClient.Builder、VectorStore 和 Prompt 模板资源。
     *
     * @param chatClientBuilder Spring AI 自动配置的 ChatClient.Builder
     * @param vectorStore       内存向量库（含已向量化的团队规范）
     * @param promptResource    从 classpath:prompts/review-prompt.st 加载的模板文件
     */
    public AiReviewService(ChatClient.Builder chatClientBuilder,
                           VectorStore vectorStore,
                           @Value("classpath:prompts/review-prompt.st") Resource promptResource) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.promptTemplate = promptResource.getContentAsString(StandardCharsets.UTF_8);
        log.info("AI Review Service 初始化完成，Prompt 模板已加载 ({} 字符)", promptTemplate.length());
    }

    /**
     * 对 PR 变更内容进行 AI 代码审查。
     *
     * @param prTitle       PR 标题
     * @param prDescription PR 描述
     * @param diffContent   代码 Diff 内容
     * @return 结构化的审查结果
     */
    public ReviewResult review(String prTitle, String prDescription, String diffContent) {
        log.info("开始 AI Review 分析...");

        // 1. RAG 检索：从向量库中查找与本 Chunk 最相关的团队规范
        String teamConventions = retrieveTeamConventions(diffContent);

        // 2. 创建 BeanOutputConverter，用于强制大模型输出符合 ReviewResult 结构的 JSON
        BeanOutputConverter<ReviewResult> converter = new BeanOutputConverter<>(ReviewResult.class);

        // 3. 构建 User Message：Prompt 模板填充 + 团队规范 + JSON Schema 格式约束
        String userMessage = buildUserMessage(prTitle, prDescription, diffContent,
                teamConventions, converter);

        // 4. 调用大模型
        long startTime = System.currentTimeMillis();
        String aiResponse = chatClient.prompt()
                .user(userMessage)
                .call()
                .content();
        long elapsed = System.currentTimeMillis() - startTime;
        log.info("AI 响应完成，耗时 {} ms，响应长度 {} 字符", elapsed,
                aiResponse != null ? aiResponse.length() : 0);

        // 5. 将 AI 返回的 JSON 转换为 ReviewResult 实体
        ReviewResult result = converter.convert(aiResponse);
        log.info("Review 结果解析成功: summary 长度={}, risks 数量={}, suggestions 数量={}",
                result.summary() != null ? result.summary().length() : 0,
                result.risks() != null ? result.risks().size() : 0,
                result.suggestions() != null ? result.suggestions().size() : 0);

        return result;
    }

    /**
     * 流式 AI 代码审查 — 逐 Token 返回。
     * <p>
     * 与 {@link #review} 使用相同的 Prompt 模板和格式约束，
     * 但通过 {@code ChatClient.stream().content()} 获取 {@link Flux}，
     * 由调用方（Controller）通过 SSE 推送给前端，实现打字机效果。
     *
     * @param prTitle       PR 标题
     * @param prDescription PR 描述
     * @param diffContent   代码 Diff 内容
     * @return 逐 Token 的字符串流，所有 Token 拼接后为完整 JSON
     */
    public Flux<String> reviewStream(String prTitle, String prDescription, String diffContent) {
        log.info("开始 AI Review 流式分析...");

        String teamConventions = retrieveTeamConventions(diffContent);
        BeanOutputConverter<ReviewResult> converter = new BeanOutputConverter<>(ReviewResult.class);
        String userMessage = buildUserMessage(prTitle, prDescription, diffContent,
                teamConventions, converter);

        log.info("流式请求已发送 (含{}条团队规范)，等待模型逐 Token 输出...",
                teamConventions.isBlank() ? 0 : "相关");
        return chatClient.prompt()
                .user(userMessage)
                .stream()
                .content();
    }

    // ==================== 并行分片审查 ====================

    /**
     * 使用 Java 21 虚拟线程并发审查多个 Diff 分片，等待全部完成后聚合结果。
     * <p>
     * <b>并发策略：</b>
     * 使用 {@link Executors#newVirtualThreadPerTaskExecutor()} 为每个分片创建独立的虚拟线程。
     * 虚拟线程由 JVM 在少量 OS 线程上调度，启动成本极低（~1μs），可以轻松承载数十个并发分片。
     * <p>
     * <b>容错设计：</b>
     * 单个分片审查失败（超时 / AI 返回异常 JSON）不会导致整体失败 —
     * 失败的 Chunk 被记录日志后丢弃，仅合并成功的分片结果。
     *
     * @param prTitle          PR 标题
     * @param prDescription    PR 描述
     * @param chunks           Diff 分片列表
     * @param progressCallback 进度回调（可选），每完成一个分片触发一次
     * @return 聚合后的审查结果
     */
    public ReviewResult reviewChunksParallel(String prTitle, String prDescription,
                                              List<String> chunks,
                                              Consumer<String> progressCallback) {
        int total = chunks.size();
        log.info("开始并行审查 {} 个 Diff 分片...", total);

        AtomicInteger completedCount = new AtomicInteger(0);
        List<CompletableFuture<ReviewResult>> futures = new ArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {

            // 为每个分片提交异步审查任务
            for (int i = 0; i < chunks.size(); i++) {
                final int chunkIdx = i;
                final String chunk = chunks.get(i);

                CompletableFuture<ReviewResult> future = CompletableFuture.supplyAsync(() -> {
                    log.info("[分片 {}/{}] 开始调用 AI 审查...", chunkIdx + 1, total);
                    try {
                        ReviewResult r = review(prTitle, prDescription, chunk);
                        int done = completedCount.incrementAndGet();
                        log.info("[分片 {}/{}] 审查完成 ({}/{})", chunkIdx + 1, total, done, total);

                        // 通知进度
                        if (progressCallback != null) {
                            progressCallback.accept("分片审查已完成 " + done + "/" + total);
                        }

                        // 标记分片序号到各条目的 location 前缀
                        return annotateChunkIndex(r, chunkIdx + 1, total);

                    } catch (Exception e) {
                        completedCount.incrementAndGet();
                        log.error("[分片 {}/{}] 审查失败，已跳过: {}", chunkIdx + 1, total, e.getMessage());
                        return null;
                    }
                }, executor);

                futures.add(future);
            }

            // 等待所有分片完成（每个分片最多等 180 秒）
            List<ReviewResult> results = futures.stream()
                    .map(f -> {
                        try {
                            return f.get(180, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            log.error("等待分片结果超时或异常: {}", e.getMessage());
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList();

            log.info("并行审查结束: 成功 {}/{} 个分片", results.size(), total);

            // 聚合
            if (results.isEmpty()) {
                return new ReviewResult(
                        "所有 " + total + " 个分片审查均失败，请检查 AI 服务状态后重试",
                        List.of(),
                        List.of()
                );
            }

            return mergeResults(prTitle, results);

        } // try-with-resources — executor.close() 确保资源释放
    }

    // ==================== 结果聚合与去重 ====================

    /**
     * 聚合多个分片的审查结果。
     * <ul>
     *   <li>summary — 各分片摘要按序拼接</li>
     *   <li>risks — 合并后按 location+description 精确去重</li>
     *   <li>suggestions — 合并后按 location+description 精确去重</li>
     * </ul>
     */
    private ReviewResult mergeResults(String prTitle, List<ReviewResult> results) {
        StringBuilder summaryBuilder = new StringBuilder();
        summaryBuilder.append("PR \"").append(prTitle).append("\"")
                .append(" 的代码审查报告（共 ").append(results.size()).append(" 个 Diff 分片）：\n");

        List<RiskItem> allRisks = new ArrayList<>();
        List<SuggestionItem> allSuggestions = new ArrayList<>();

        for (ReviewResult r : results) {
            if (r.summary() != null && !r.summary().isBlank()) {
                summaryBuilder.append(r.summary()).append("\n");
            }
            if (r.risks() != null) {
                allRisks.addAll(r.risks());
            }
            if (r.suggestions() != null) {
                allSuggestions.addAll(r.suggestions());
            }
        }

        List<RiskItem> uniqueRisks = deduplicateRisks(allRisks);
        List<SuggestionItem> uniqueSuggestions = deduplicateSuggestions(allSuggestions);

        log.info("结果聚合完成: risks {} → {} (去重 {}), suggestions {} → {} (去重 {})",
                allRisks.size(), uniqueRisks.size(), allRisks.size() - uniqueRisks.size(),
                allSuggestions.size(), uniqueSuggestions.size(), allSuggestions.size() - uniqueSuggestions.size());

        return new ReviewResult(
                summaryBuilder.toString().trim(),
                uniqueRisks,
                uniqueSuggestions
        );
    }

    /**
     * 风险项去重 — 以 (location, description) 精确匹配为 key。
     * <p>
     * 重叠分片中的同一段代码可能被两个相邻窗口分别审查并报告相同问题，
     * 此方法确保最终报告中每个问题只出现一次。
     */
    private List<RiskItem> deduplicateRisks(List<RiskItem> risks) {
        Set<String> seen = new HashSet<>();
        List<RiskItem> unique = new ArrayList<>();
        for (RiskItem r : risks) {
            // 去掉分片前缀后再比较，避免 "分片1/3" vs "分片2/3" 导致去重失败
            String location = stripChunkPrefix(r.location());
            String key = location + "|||" + r.description();
            if (seen.add(key)) {
                unique.add(r);
            }
        }
        return unique;
    }

    /** 同 deduplicateRisks，去重建议项 */
    private List<SuggestionItem> deduplicateSuggestions(List<SuggestionItem> items) {
        Set<String> seen = new HashSet<>();
        List<SuggestionItem> unique = new ArrayList<>();
        for (SuggestionItem s : items) {
            String location = stripChunkPrefix(s.location());
            String key = location + "|||" + s.description();
            if (seen.add(key)) {
                unique.add(s);
            }
        }
        return unique;
    }

    /** 去掉 location 中的 "[分片 X/N] " 前缀，便于跨分片去重 */
    private String stripChunkPrefix(String location) {
        if (location == null) return "";
        return location.replaceFirst("^\\[分片 \\d+/\\d+\\] ", "");
    }

    /**
     * 为每条风险/建议的 location 添加分片序号前缀，方便前端定位问题来源。
     */
    private ReviewResult annotateChunkIndex(ReviewResult result, int chunkIdx, int total) {
        if (result == null) return null;
        String prefix = "[分片 " + chunkIdx + "/" + total + "] ";

        List<RiskItem> annotatedRisks = result.risks() != null
                ? result.risks().stream()
                    .map(r -> new RiskItem(
                            r.severity(), r.category(),
                            prefix + (r.location() != null ? r.location() : "未知位置"),
                            r.description(), r.recommendation()))
                    .toList()
                : List.of();

        List<SuggestionItem> annotatedSuggestions = result.suggestions() != null
                ? result.suggestions().stream()
                    .map(s -> new SuggestionItem(
                            prefix + (s.location() != null ? s.location() : "未知位置"),
                            s.description(), s.refactoredCode()))
                    .toList()
                : List.of();

        return new ReviewResult(result.summary(), annotatedRisks, annotatedSuggestions);
    }

    /**
     * 拼装完整的 User Message：Prompt 模板 + 团队规范 + JSON Schema 格式要求。
     * <p>
     * BeanOutputConverter.getFormat() 会生成类似 JSON Schema 的格式约束文本，
     * 附加在 Prompt 末尾可以有效引导大模型输出符合结构的 JSON。
     */
    private String buildUserMessage(String prTitle, String prDescription,
                                     String diffContent, String teamConventions,
                                     BeanOutputConverter<ReviewResult> converter) {
        // 规范为空时填入提示语，避免占位符残留
        String conventionsText = (teamConventions != null && !teamConventions.isBlank())
                ? teamConventions
                : "（无额外团队规范约束，按通用最佳实践审查）";

        return promptTemplate
                .replace("{prTitle}", prTitle != null ? prTitle : "无")
                .replace("{prDescription}", prDescription != null ? prDescription : "无描述")
                .replace("{diffContent}", diffContent != null ? diffContent : "（无变更内容）")
                .replace("{teamConventions}", conventionsText)
                + "\n\n--- 输出格式要求（严格遵守 JSON Schema）---\n"
                + converter.getFormat();
    }

    // ==================== RAG 检索逻辑 ====================

    /**
     * 从向量库中检索与当前 Diff Chunk 最相关的团队规范。
     * <p>
     * <b>Query 构造策略：</b>
     * 使用 Diff Chunk 的前 {@link #QUERY_MAX_LENGTH} 个字符作为查询词。
     * <p>
     * <b>理由：</b>
     * Unified Diff 格式的开头部分包含最关键的结构化信息：
     * <ol>
     *   <li>文件路径（{@code diff --git a/Xxx.java b/Xxx.java}）— 表明涉及的模块</li>
     *   <li>变更位置（{@code @@ -10,6 +10,15 @@}）— 定位改动的类/方法</li>
     *   <li>上下文代码 — 提供足够的语义线索</li>
     * </ol>
     * 截取前 500 字符足以覆盖这些信息，同时避免过长查询稀释向量相似度的精度。
     * <p>
     * <b>备选方案比较：</b>
     * <ul>
     *   <li>PR 标题作为 Query：维度太高，缺少代码级语义</li>
     *   <li>完整 Diff 作为 Query：过长，Embedding 模型可能截断或稀释关键信息</li>
     *   <li>随机采样行：可能丢失文件路径等核心元信息</li>
     * </ul>
     *
     * @param diffContent Diff Chunk 文本
     * @return 格式化后的团队规范文本；若向量库为空或检索失败，返回空字符串
     */
    private String retrieveTeamConventions(String diffContent) {
        try {
            // 提取 Query：取 Diff 前 N 个字符
            String query = diffContent != null && diffContent.length() > QUERY_MAX_LENGTH
                    ? diffContent.substring(0, QUERY_MAX_LENGTH)
                    : (diffContent != null ? diffContent : "");

            if (query.isBlank()) {
                return "";
            }

            // 向量相似度检索
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(TOP_K_CONVENTIONS)
                            .build());

            if (results == null || results.isEmpty()) {
                log.debug("未检索到相关团队规范");
                return "";
            }

            // 组装规范文本
            String conventions = results.stream()
                    .map(Document::getText)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("\n\n"));

            log.debug("检索到 {} 条相关团队规范 (Query 长度: {} 字符)", results.size(), query.length());
            return conventions;

        } catch (Exception e) {
            // 检索失败不应中断主流程 — 降级为无规范约束
            log.warn("团队规范检索失败，本次审查将不注入规范约束: {}", e.getMessage());
            return "";
        }
    }
}
