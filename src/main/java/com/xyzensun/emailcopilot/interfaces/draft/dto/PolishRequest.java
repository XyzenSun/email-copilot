package com.xyzensun.emailcopilot.interfaces.draft.dto;

/** AI 润色请求（openapi {@code PolishRequest}）。 */
public record PolishRequest(
        String bodyText,
        String instruction) {
}
