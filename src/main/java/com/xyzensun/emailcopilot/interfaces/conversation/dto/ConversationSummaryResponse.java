package com.xyzensun.emailcopilot.interfaces.conversation.dto;

import com.xyzensun.emailcopilot.application.conversation.model.ConversationSummaryView;

import java.time.OffsetDateTime;

public record ConversationSummaryResponse(
        long id,
        String title,
        boolean archived,
        OffsetDateTime updatedAt) {

    public static ConversationSummaryResponse from(ConversationSummaryView view) {
        return new ConversationSummaryResponse(
                view.id(), view.title(), view.archived(), view.updatedAt());
    }
}
