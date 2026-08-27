package com.xyzensun.emailcopilot.interfaces.draft.dto;

/** AI 润色结果响应（openapi {@code PolishResult}）。返回建议，不落库。 */
public record PolishResultResponse(
        String polishedText) {
}
