package org.fourerif.controller;

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

    public ReviewController(GitHubService gitHubService, AiReviewService aiReviewService) {
        this.gitHubService = gitHubService;
        this.aiReviewService = aiReviewService;
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

        // 1. 解析 PR URL，提取 owner、repo、prNumber
        ParsedPrUrl parsed = gitHubService.parsePrUrl(prUrl)
                .orElseThrow(() -> {
                    log.warn("无效的 PR URL: {}", prUrl);
                    return new IllegalArgumentException("无效的 GitHub PR URL，格式应为: https://github.com/{owner}/{repo}/pull/{number}");
                });

        // 2. 获取 PR 基本信息（标题、描述）
        PrInfo prInfo = gitHubService.getPrInfo(parsed.owner(), parsed.repo(), parsed.prNumber());
        log.info("PR 信息: title={}", prInfo.title());

        // 3. 获取 PR 的 Diff 内容
        String diff = gitHubService.getPrDiff(parsed.owner(), parsed.repo(), parsed.prNumber());

        // 4. 调用 AI 进行代码审查
        ReviewResult result = aiReviewService.review(prInfo.title(), prInfo.description(), diff);

        // 5. 返回结构化结果
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

        // 2. 获取 PR 信息及 Diff（同步，需等待）
        PrInfo prInfo = gitHubService.getPrInfo(parsed.owner(), parsed.repo(), parsed.prNumber());
        log.info("PR 信息: title={}", prInfo.title());
        String diff = gitHubService.getPrDiff(parsed.owner(), parsed.repo(), parsed.prNumber());

        // 3. 创建 SseEmitter（120 秒超时，覆盖最长推理时间）
        SseEmitter emitter = new SseEmitter(120_000L);

        // 4. 异步启动 AI 流式调用，逐 Token 推送至客户端
        Thread.startVirtualThread(() -> {
            try {
                Flux<String> tokenStream = aiReviewService.reviewStream(
                        prInfo.title(), prInfo.description(), diff);

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

        // 5. 注册超时和错误回调
        emitter.onTimeout(() -> log.warn("SSE 连接超时: prUrl={}", prUrl));
        emitter.onError(ex -> log.error("SSE 推送异常", ex));

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
}
