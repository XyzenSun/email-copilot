package com.xyzensun.emailcopilot.interfaces.conversation.dto;

import com.xyzensun.emailcopilot.application.conversation.model.TurnView;

import java.time.OffsetDateTime;

public record TurnResponse(
        long id,
        String status,
        String userMessage,
        String finalAnswer,
        Integer modelCallCount,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {

    public static TurnResponse from(TurnView view) {
        return new TurnResponse(
                view.id(), view.status(), view.userMessage(),
                view.finalAnswer(), view.modelCallCount(),
                view.startedAt(), view.finishedAt());
    }
}
