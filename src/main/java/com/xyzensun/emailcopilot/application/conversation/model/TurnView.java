package com.xyzensun.emailcopilot.application.conversation.model;

import java.time.OffsetDateTime;

/**
 * 单轮对话的视图。finalAnswer 不经 HTML 转换（来自 provider 文本输出，已安全）。
 */
public record TurnView(
        long id,
        String status,
        String userMessage,
        String finalAnswer,
        Integer modelCallCount,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {
}
