package org.fourerif.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * AI Review 结构化输出结果 — 包含 PR 摘要、风险列表和改进建议。
 * 该 Record 与 Spring AI 的 BeanOutputConverter 配合使用，
 * 强制大模型按照此结构返回 JSON。
 *
 * @param summary     PR 变更意图总结
 * @param risks       识别到的潜在风险列表
 * @param suggestions 具体的改进建议和重构示例
 */
public record ReviewResult(
    @JsonProperty("summary") String summary,
    @JsonProperty("risks") List<RiskItem> risks,
    @JsonProperty("suggestions") List<SuggestionItem> suggestions
) {}