package com.xyzensun.emailcopilot.interfaces.conversation.dto;

import com.xyzensun.emailcopilot.application.conversation.model.PendingActionDetailView;
import com.xyzensun.emailcopilot.domain.Recipients;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 待审批提案详情。审批卡片文字由代码从这些字段渲染，不由 AI 自述。
 */
public record PendingActionDetailResponse(
        long id,
        String actionType,
        String approvalStatus,
        List<Long> targetMessageIds,
        ContentSnapshotResponse content,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        OffsetDateTime decidedAt,
        String cancelReason,
        String executionStatus) {

    public record ContentSnapshotResponse(
            long fromMailAccountId,
            Long inReplyToMessageId,
            Recipients recipients,
            String subject,
            String bodyText) {
    }

    public static PendingActionDetailResponse from(PendingActionDetailView view) {
        ContentSnapshotResponse content = null;
        if (view.content() != null) {
            content = new ContentSnapshotResponse(
                    view.content().fromMailAccountId(),
                    view.content().inReplyToMessageId(),
                    view.content().recipients(),
                    view.content().subject(),
                    view.content().bodyText());
        }
        return new PendingActionDetailResponse(
                view.id(), view.actionType(), view.approvalStatus(),
                view.targetMessageIds(), content,
                view.createdAt(), view.expiresAt(),
                view.decidedAt(), view.cancelReason(),
                view.executionStatus());
    }
}
