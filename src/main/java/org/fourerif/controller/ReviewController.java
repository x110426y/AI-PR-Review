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
     * AI 代码审查接口。
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
}
