package com.xyzensun.emailcopilot.interfaces.conversation.dto;

import com.xyzensun.emailcopilot.application.conversation.model.ConversationDetailView;

import java.time.OffsetDateTime;
import java.util.List;

public record ConversationDetailResponse(
        long id,
        String title,
        boolean archived,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<TurnResponse> turns) {

    public static ConversationDetailResponse from(
            ConversationDetailView view, List<TurnResponse> turns) {
        return new ConversationDetailResponse(
                view.id(), view.title(), view.archived(),
                view.createdAt(), view.updatedAt(), turns);
    }
}
