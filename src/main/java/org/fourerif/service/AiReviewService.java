package org.fourerif.service;

import org.fourerif.dto.ReviewResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

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

    /** 单次 diff 最大长度，超出的部分在 GitHubService 已截断 */
    private static final int MAX_DIFF_LENGTH = 20000;

    private final ChatClient chatClient;
    private final String promptTemplate;

    /**
     * 构造函数注入 ChatClient.Builder 和 Prompt 模板资源。
     *
     * @param chatClientBuilder Spring AI 自动配置的 ChatClient.Builder
     * @param promptResource    从 classpath:prompts/review-prompt.st 加载的模板文件
     */
    public AiReviewService(ChatClient.Builder chatClientBuilder,
                           @Value("classpath:prompts/review-prompt.st") Resource promptResource) throws IOException {
        this.chatClient = chatClientBuilder.build();
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

        // 1. 创建 BeanOutputConverter，用于强制大模型输出符合 ReviewResult 结构的 JSON
        BeanOutputConverter<ReviewResult> converter = new BeanOutputConverter<>(ReviewResult.class);

        // 2. 构建 User Message：Prompt 模板填充 + JSON Schema 格式约束
        String userMessage = buildUserMessage(prTitle, prDescription, diffContent, converter);

        // 3. 调用大模型
        long startTime = System.currentTimeMillis();
        String aiResponse = chatClient.prompt()
                .user(userMessage)
                .call()
                .content();
        long elapsed = System.currentTimeMillis() - startTime;
        log.info("AI 响应完成，耗时 {} ms，响应长度 {} 字符", elapsed,
                aiResponse != null ? aiResponse.length() : 0);

        // 4. 将 AI 返回的 JSON 转换为 ReviewResult 实体
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

        BeanOutputConverter<ReviewResult> converter = new BeanOutputConverter<>(ReviewResult.class);
        String userMessage = buildUserMessage(prTitle, prDescription, diffContent, converter);

        log.info("流式请求已发送，等待模型逐 Token 输出...");
        return chatClient.prompt()
                .user(userMessage)
                .stream()
                .content();
    }

    /**
     * 拼装完整的 User Message：Prompt 模板 + JSON Schema 格式要求。
     * <p>
     * BeanOutputConverter.getFormat() 会生成类似 JSON Schema 的格式约束文本，
     * 附加在 Prompt 末尾可以有效引导大模型输出符合结构的 JSON。
     */
    private String buildUserMessage(String prTitle, String prDescription,
                                     String diffContent, BeanOutputConverter<ReviewResult> converter) {
        return promptTemplate
                .replace("{prTitle}", prTitle != null ? prTitle : "无")
                .replace("{prDescription}", prDescription != null ? prDescription : "无描述")
                .replace("{diffContent}", diffContent != null ? diffContent : "（无变更内容）")
                + "\n\n--- 输出格式要求（严格遵守 JSON Schema）---\n"
                + converter.getFormat();
    }
}
