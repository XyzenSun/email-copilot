package com.xyzensun.emailcopilot.application.conversation.model;

import com.xyzensun.emailcopilot.domain.Recipients;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 待审批提案详情：send_email / save_draft 带 {@link ContentSnapshot}，
 * local_delete 带 {@code targetMessageIds}。
 *
 * <p>审批卡片文字由代码从这些字段渲染，不由 AI 生成的概述。
 */
public record PendingActionDetailView(
        long id,
        String actionType,
        String approvalStatus,
        List<Long> targetMessageIds,
        ContentSnapshot content,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        OffsetDateTime decidedAt,
        String cancelReason,
        String executionStatus) {

    /** send_email / save_draft 的不可变内容快照。local_delete 时为 null。 */
    public record ContentSnapshot(
            long fromMailAccountId,
            Long inReplyToMessageId,
            Recipients recipients,
            String subject,
            String bodyText) {
    }
}
