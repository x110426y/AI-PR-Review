package org.fourerif.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 建议项 — 表示 AI 针对某段代码给出的具体改进建议和重构示例。
 *
 * @param location        建议对应的文件位置
 * @param description     当前代码存在的问题简述
 * @param refactoredCode  重构后的代码示例
 */
public record SuggestionItem(
    @JsonProperty("location") String location,
    @JsonProperty("description") String description,
    @JsonProperty("refactoredCode") String refactoredCode
) {}