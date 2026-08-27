package com.xyzensun.emailcopilot.interfaces.mail.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 手动重新处理请求（openapi {@code ReprocessRequest}）。
 *
 * <p>{@code stage} 取四值枚举的字面值（{@code spam_judgment}/{@code classification}/
 * {@code translation}/{@code summary}）；其余值（含 {@code sender_rule}/
 * {@code language_detection}/{@code done}）由 Controller 解析时落 400 {@code VALIDATION_FAILED}。
 */
public record ReprocessRequest(
        @NotBlank(message = "要重新处理的阶段不能为空") String stage) {
}
