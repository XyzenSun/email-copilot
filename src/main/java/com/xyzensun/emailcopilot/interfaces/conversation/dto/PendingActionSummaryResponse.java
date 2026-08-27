package com.xyzensun.emailcopilot.interfaces.conversation.dto;

import com.xyzensun.emailcopilot.application.conversation.model.PendingActionSummaryView;

import java.time.OffsetDateTime;

public record PendingActionSummaryResponse(
        long id,
        String actionType,
        String approvalStatus,
        Integer targetCount,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        OffsetDateTime decidedAt,
        String cancelReason) {

    public static PendingActionSummaryResponse from(PendingActionSummaryView view) {
        return new PendingActionSummaryResponse(
                view.id(), view.actionType(), view.approvalStatus(),
                view.targetCount(), view.createdAt(), view.expiresAt(),
                view.decidedAt(), view.cancelReason());
    }
}
