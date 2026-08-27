package com.xyzensun.emailcopilot.interfaces.draft.dto;

/** 发信结果响应（openapi {@code SendResult}）。三种结果全部返回 HTTP 200。 */
public record SendResultResponse(
        String status,
        Long messageId,
        String resultMessage) {
}
