package com.xyzensun.emailcopilot.application.conversation.model;

import java.time.OffsetDateTime;

/**
 * 对话列表项（{@code API.md} §12.x）。
 */
public record ConversationSummaryView(
        long id,
        String title,
        boolean archived,
        OffsetDateTime updatedAt) {
}
