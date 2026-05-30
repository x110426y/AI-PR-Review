package org.fourerif.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 风险项 — 表示 AI 识别出的单个潜在风险。
 *
 * @param severity     风险严重等级（HIGH / MEDIUM / LOW）
 * @param category     风险类别（SECURITY / PERFORMANCE / LOGIC / STYLE）
 * @param location     风险所在文件和大致位置
 * @param description  风险的具体描述
 * @param recommendation 修复或改进建议
 */
public record RiskItem(
    @JsonProperty("severity") String severity,
    @JsonProperty("category") String category,
    @JsonProperty("location") String location,
    @JsonProperty("description") String description,
    @JsonProperty("recommendation") String recommendation
) {}