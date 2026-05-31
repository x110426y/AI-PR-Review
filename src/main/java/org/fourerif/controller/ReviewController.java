package org.fourerif.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fourerif.dto.ReviewResult;
import org.fourerif.service.AiReviewService;
import org.fourerif.service.GitHubService;
import org.fourerif.service.GitHubService.ParsedPrUrl;
import org.fourerif.service.GitHubService.PrInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;

/**
 * PR Review REST 控制器。
 * <p>
 * 对外暴露 {@code GET /api/review} 接口，接收 GitHub PR 链接，
 * 协调 {@link GitHubService} 获取 PR 数据，
 * 再由 {@link AiReviewService} 调用大模型进行代码审查，
 * 最终返回结构化的 {@link ReviewResult}。
 */
@RestController
@RequestMapping("/api")
public class ReviewController {

    private static final Logger log = LoggerFactory.getLogger(ReviewController.class);

    private final GitHubService gitHubService;
    private final AiReviewService aiReviewService;
    private final ObjectMapper objectMapper;

    public ReviewController(GitHubService gitHubService, AiReviewService aiReviewService,
                            ObjectMapper objectMapper) {
        this.gitHubService = gitHubService;
        this.aiReviewService = aiReviewService;
        this.objectMapper = objectMapper;
    }

    /**
     * AI 代码审查接口（全量响应）。
     *
     * @param prUrl GitHub Pull Request 链接，例如 https://github.com/spring-projects/spring-ai/pull/123
     * @return 结构化审查结果，包含 PR 摘要、风险列表和重构建议
     */
    @GetMapping("/review")
    public ResponseEntity<?> review(@RequestParam("prUrl") String prUrl) {
        log.info("收到 Review 请求: prUrl={}", prUrl);

        // 1. 解析 PR URL
        ParsedPrUrl parsed = gitHubService.parsePrUrl(prUrl)
                .orElseThrow(() -> {
                    log.warn("无效的 PR URL: {}", prUrl);
                    return new IllegalArgumentException("无效的 GitHub PR URL，格式应为: https://github.com/{owner}/{repo}/pull/{number}");
                });

        // 2. 获取 PR 基本信息
        PrInfo prInfo = gitHubService.getPrInfo(parsed.owner(), parsed.repo(), parsed.prNumber());
        log.info("PR 信息: title={}", prInfo.title());

        // 3. 获取 Diff 并按滑动窗口分片
        List<String> chunks = gitHubService.getPrDiffChunked(
                parsed.owner(), parsed.repo(), parsed.prNumber());

        // 4. 单分片直接审查；多分片并行审查 + 聚合
        ReviewResult result;
        if (chunks.size() == 1) {
            result = aiReviewService.review(prInfo.title(), prInfo.description(), chunks.get(0));
        } else {
            log.info("PR 过大，启动并行审查 ({} 个分片)...", chunks.size());
            result = aiReviewService.reviewChunksParallel(
                    prInfo.title(), prInfo.description(), chunks, null);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * AI 代码审查接口（SSE 流式响应）。
     * <p>
     * 与 {@code /review} 接口逻辑相同，但输出采用 Server-Sent Events 逐 Token 推送，
     * 前端可实时渲染 AI 生成过程（打字机效果），显著降低用户等待焦虑。
     * <p>
     * 事件格式：
     * <ul>
     *   <li>{@code data: <token>} — 每个数据块，拼接后为完整 JSON</li>
     *   <li>{@code event: done \n data: complete} — 流结束标记</li>
     * </ul>
     *
     * @param prUrl GitHub Pull Request 链接
     * @return SseEmitter，异步推送 AI 输出
     */
    @GetMapping("/review/stream")
    public SseEmitter reviewStream(@RequestParam("prUrl") String prUrl) {
        log.info("收到 Review 流式请求: prUrl={}", prUrl);

        // 1. 解析 PR URL
        ParsedPrUrl parsed = gitHubService.parsePrUrl(prUrl)
                .orElseThrow(() -> {
                    log.warn("无效的 PR URL: {}", prUrl);
                    return new IllegalArgumentException("无效的 GitHub PR URL，格式应为: https://github.com/{owner}/{repo}/pull/{number}");
                });

        // 2. 获取 PR 信息及 Diff
        PrInfo prInfo = gitHubService.getPrInfo(parsed.owner(), parsed.repo(), parsed.prNumber());
        log.info("PR 信息: title={}", prInfo.title());
        List<String> chunks = gitHubService.getPrDiffChunked(
                parsed.owner(), parsed.repo(), parsed.prNumber());

        // 3. 单分片 → 逐 Token 流式推送（打字机体验）
        if (chunks.size() == 1) {
            return streamSingleChunk(prInfo, chunks.get(0));
        }

        // 4. 多分片 → 并行审查 + SSE 进度通知 + 最终一次性推送聚合 JSON
        return streamMultiChunk(prInfo, chunks);
    }

    /**
     * 单分片流式审查 — 逐 Token SSE 推送，保持打字机体验。
     */
    private SseEmitter streamSingleChunk(PrInfo prInfo, String chunk) {
        SseEmitter emitter = new SseEmitter(120_000L);

        Thread.startVirtualThread(() -> {
            try {
                Flux<String> tokenStream = aiReviewService.reviewStream(
                        prInfo.title(), prInfo.description(), chunk);

                tokenStream
                        .doOnNext(token -> sendData(emitter, token))
                        .doOnComplete(() -> {
                            sendDone(emitter);
                            emitter.complete();
                            log.info("SSE 流式推送完成");
                        })
                        .doOnError(error -> {
                            log.error("AI 流式调用失败", error);
                            emitter.completeWithError(error);
                        })
                        .subscribe();
            } catch (Exception e) {
                log.error("SSE 流启动失败", e);
                emitter.completeWithError(e);
            }
        });

        emitter.onTimeout(() -> log.warn("SSE 连接超时"));
        emitter.onError(ex -> log.error("SSE 推送异常", ex));
        return emitter;
    }

    /**
     * 多分片并行审查 — SSE 推送进度事件，完成后一次性推送聚合 JSON。
     * <p>
     * 事件序列：
     * <ol>
     *   <li>{@code event: info \n data: {"chunks":N}} — 分片总数</li>
     *   <li>{@code event: progress \n data: 分片审查已完成 X/N} — 每完成一个分片触发一次</li>
     *   <li>{@code data: <聚合后的 JSON>} — 最终结果（无名事件，由 onmessage 接收）</li>
     *   <li>{@code event: done \n data: complete} — 流结束</li>
     * </ol>
     */
    private SseEmitter streamMultiChunk(PrInfo prInfo, List<String> chunks) {
        int total = chunks.size();
        log.info("多分片并行审查: {} 个分片", total);

        SseEmitter emitter = new SseEmitter(300_000L);   // 5 分钟超时

        Thread.startVirtualThread(() -> {
            try {
                // 发送分片总数
                sendNamedEvent(emitter, "info", "{\"chunks\":" + total + "}");

                // 并行审查，每完成一个分片推送进度
                ReviewResult result = aiReviewService.reviewChunksParallel(
                        prInfo.title(), prInfo.description(), chunks,
                        progressMsg -> sendNamedEvent(emitter, "progress", progressMsg)
                );

                // 推送聚合后的最终 JSON
                String json = objectMapper.writeValueAsString(result);
                sendData(emitter, json);
                sendDone(emitter);
                emitter.complete();
                log.info("多分片审查完成，聚合结果已推送");
            } catch (Exception e) {
                log.error("多分片审查失败", e);
                emitter.completeWithError(e);
            }
        });

        emitter.onTimeout(() -> log.warn("多分片 SSE 连接超时: {} 个分片", total));
        emitter.onError(ex -> log.error("多分片 SSE 推送异常", ex));
        return emitter;
    }

    /**
     * 发送一个 Token 数据块（无名事件，由前端 onmessage 接收）。
     */
    private void sendData(SseEmitter emitter, String data) {
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (IOException e) {
            log.debug("SSE 客户端已断开，停止推送");
        }
    }

    /**
     * 发送流结束标记（命名事件 "done"，由前端 addEventListener('done') 接收）。
     */
    private void sendDone(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("done").data("complete"));
        } catch (IOException e) {
            log.debug("SSE 客户端已断开");
        }
    }

    /**
     * 发送命名事件（如 progress、info），仅在前端显式监听时接收，
     * 不会污染 onmessage 的 JSON 累积流。
     */
    private void sendNamedEvent(SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            log.debug("SSE 客户端已断开，停止推送");
        }
    }
}
