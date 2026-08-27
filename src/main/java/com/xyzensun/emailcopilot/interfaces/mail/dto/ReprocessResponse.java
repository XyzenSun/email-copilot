package com.xyzensun.emailcopilot.interfaces.mail.dto;

/**
 * 手动重新处理响应（openapi {@code ReprocessResponse}）。
 *
 * <p>同步返回（{@code design.md} §3.2，与 approve 一致）：{@code succeeded} 时 {@code message}
 * 是写回后的刷新详情；{@code failed} 时 AI 试了但没给出可用结果，{@code errorCode} 标注原因
 * （{@code AI_PROVIDER_FAILURE}/{@code AI_STRUCTURED_OUTPUT_INVALID}），产物未改。
 */
public record ReprocessResponse(
        String status,
        String errorCode,
        MessageDetailResponse message) {
}
