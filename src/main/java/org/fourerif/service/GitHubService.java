package org.fourerif.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

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

    /** 单次 diff 最大字符数（约 20000 字符），超出部分截断以避免超出大模型上下文窗口 */
    private static final int MAX_DIFF_LENGTH = 20000;

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
     * 获取 PR 的 Diff 内容。
     *
     * @param owner    仓库所有者
     * @param repo     仓库名称
     * @param prNumber PR 编号
     * @return Diff 文本（已截断处理）
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
            return truncateDiff(diff);
        } catch (Exception e) {
            log.error("获取 PR Diff 失败", e);
            throw new RuntimeException("无法获取 PR Diff: " + e.getMessage(), e);
        }
    }

    /**
     * 截断过长的 Diff 文本，防止超出大模型的上下文窗口。
     * 当内容超长时，保留前 MAX_DIFF_LENGTH 个字符并附加截断提示。
     */
    private String truncateDiff(String diff) {
        if (diff == null || diff.isEmpty()) {
            return "（无代码变更）";
        }
        if (diff.length() <= MAX_DIFF_LENGTH) {
            return diff;
        }
        log.warn("Diff 内容过长 ({} 字符)，已截断至 {} 字符", diff.length(), MAX_DIFF_LENGTH);
        return diff.substring(0, MAX_DIFF_LENGTH)
                + "\n\n... (Diff 内容过长，已截断。完整变更请查看原始 PR 链接)";
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
