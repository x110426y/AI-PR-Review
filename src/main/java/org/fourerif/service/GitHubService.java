package org.fourerif.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub 服务 — 负责解析 PR URL 并调用 GitHub REST API 获取 PR 元数据和 Diff。
 * <p>
 * 使用 Spring 6.1+ 内置的 {@link RestClient} 实现 HTTP 调用，
 * 通过 GitHub Personal Access Token 进行身份认证。
 */
@Service
public class GitHubService {

    private static final Logger log = LoggerFactory.getLogger(GitHubService.class);

    /** GitHub PR URL 解析正则：https://github.com/{owner}/{repo}/pull/{number} */
    private static final Pattern PR_URL_PATTERN =
            Pattern.compile("https?://github\\.com/([^/]+)/([^/]+)/pull/(\\d+)");

    /**
     * 滑动窗口分片参数。
     * <ul>
     *   <li>{@code WINDOW_LINES} — 每个分片包含的行数（约 800 行 ≈ 2-3 万字符）</li>
     *   <li>{@code OVERLAP_LINES} — 相邻分片重叠的行数，防止关键代码落在分片边界被遗漏</li>
     * </ul>
     */
    private static final int WINDOW_LINES = 800;
    private static final int OVERLAP_LINES = 150;

    private final RestClient restClient;
    private final String githubToken;

    public GitHubService(@Value("${github.token}") String githubToken) {
        this.githubToken = githubToken;
        this.restClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Authorization", "Bearer " + githubToken)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /**
     * 从 PR URL 中提取 owner、repo、prNumber 三元组。
     *
     * @param prUrl GitHub PR 链接
     * @return Optional 包装的 ParsedPrUrl，解析失败返回 Optional.empty()
     */
    public Optional<ParsedPrUrl> parsePrUrl(String prUrl) {
        Matcher matcher = PR_URL_PATTERN.matcher(prUrl);
        if (matcher.find()) {
            return Optional.of(new ParsedPrUrl(matcher.group(1), matcher.group(2), matcher.group(3)));
        }
        return Optional.empty();
    }

    /**
     * 获取 PR 的基本信息（标题、描述）。
     *
     * @param owner    仓库所有者
     * @param repo     仓库名称
     * @param prNumber PR 编号
     * @return PR 基本信息
     */
    public PrInfo getPrInfo(String owner, String repo, String prNumber) {
        log.info("正在获取 PR 信息: {}/{}/{}", owner, repo, prNumber);
        try {
            String json = restClient.get()
                    .uri("/repos/{owner}/{repo}/pulls/{prNumber}", owner, repo, prNumber)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        log.error("GitHub API 返回错误状态码: {}", resp.getStatusCode());
                        throw new RuntimeException("GitHub API 请求失败: " + resp.getStatusCode());
                    })
                    .body(String.class);
            return parsePrInfo(json);
        } catch (Exception e) {
            log.error("获取 PR 信息失败", e);
            throw new RuntimeException("无法获取 PR 信息: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 PR 的 Diff 原始文本（不做截断或分片）。
     *
     * @param owner    仓库所有者
     * @param repo     仓库名称
     * @param prNumber PR 编号
     * @return Diff 原始文本
     */
    public String getPrDiff(String owner, String repo, String prNumber) {
        log.info("正在获取 PR Diff: {}/{}/{}", owner, repo, prNumber);
        try {
            String diff = restClient.get()
                    .uri("/repos/{owner}/{repo}/pulls/{prNumber}", owner, repo, prNumber)
                    .header("Accept", "application/vnd.github.v3.diff")
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        log.error("GitHub API 返回错误状态码: {}", resp.getStatusCode());
                        throw new RuntimeException("GitHub API 请求失败: " + resp.getStatusCode());
                    })
                    .body(String.class);
            if (diff == null || diff.isEmpty()) {
                return "（无代码变更）";
            }
            return diff;
        } catch (Exception e) {
            log.error("获取 PR Diff 失败", e);
            throw new RuntimeException("无法获取 PR Diff: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 PR Diff 并按滑动窗口 + 重叠策略分片。
     * <p>
     * 分片后可交由 {@code AiReviewService} 使用虚拟线程并行审查，
     * 确保大型 PR 的 Diff 100% 被传递给大模型，不丢失任何代码上下文。
     *
     * @param owner    仓库所有者
     * @param repo     仓库名称
     * @param prNumber PR 编号
     * @return Diff 分片列表；若 Diff 不超出一个窗口，返回单元素列表
     */
    public List<String> getPrDiffChunked(String owner, String repo, String prNumber) {
        String diff = getPrDiff(owner, repo, prNumber);
        return chunkDiffWithOverlap(diff, WINDOW_LINES, OVERLAP_LINES);
    }

    /**
     * 滑动窗口 + 重叠分片算法。
     * <p>
     * 按行拆分 Diff，以固定行数作为一个窗口，相邻窗口之间保留指定行数的重叠区域。
     * <p>
     * <b>为什么需要重叠？</b>
     * 代码审查中许多问题跨越分片边界（如一个函数的调用方和被调用方放在不同分片中），
     * 重叠区域确保边界附近的代码在两个相邻分片中都可见，大幅降低边界漏报。
     *
     * @param diff         Diff 原始文本
     * @param windowLines  每个分片包含的行数
     * @param overlapLines 相邻分片间重叠的行数
     * @return 分片文本列表
     */
    public List<String> chunkDiffWithOverlap(String diff, int windowLines, int overlapLines) {
        if (diff == null || diff.isEmpty()) {
            return List.of("（无代码变更）");
        }

        String[] lines = diff.split("\n", -1);       // -1 保留末尾空行
        if (lines.length <= windowLines) {
            log.info("Diff 共 {} 行，不超出窗口大小 ({} 行)，无需分片", lines.length, windowLines);
            return List.of(diff);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < lines.length) {
            int end = Math.min(start + windowLines, lines.length);
            String[] slice = Arrays.copyOfRange(lines, start, end);
            chunks.add(String.join("\n", slice));

            if (end >= lines.length) break;

            // 下一窗口起点 = 当前窗口终点 - 重叠行数
            start = end - overlapLines;
        }

        log.info("Diff 共 {} 行，已拆分为 {} 个分片 (窗口={}行, 重叠={}行)",
                lines.length, chunks.size(), windowLines, overlapLines);
        return chunks;
    }

    /**
     * 简单解析 PR JSON 响应中的 title 和 body 字段。
     * 为减少依赖，采用基本的字符串提取方式；生产环境中建议使用 Jackson ObjectMapper。
     */
    private PrInfo parsePrInfo(String json) {
        String title = extractJsonField(json, "title");
        String body = extractJsonField(json, "body");
        return new PrInfo(title, body != null ? body : "（无描述）");
    }

    /** 从 JSON 字符串中提取简单字段值（仅限字符串类型字段） */
    private String extractJsonField(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    // ==================== 内部数据类 ====================

    /**
     * 解析后的 PR URL 信息。
     */
    public record ParsedPrUrl(String owner, String repo, String prNumber) {}

    /**
     * PR 基本信息。
     */
    public record PrInfo(String title, String description) {}
}
