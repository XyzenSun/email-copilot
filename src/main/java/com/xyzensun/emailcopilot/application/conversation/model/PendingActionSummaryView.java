package com.xyzensun.emailcopilot.application.conversation.model;

import java.time.OffsetDateTime;

/**
 * 待审批提案列表项。不含 targets（只给 targetCount），完整目标列表在 GET /actions/{id}。
 *
 * <p>审批卡片文字由 {@code actionType} + {@code targetCount} 渲染，不使用 AI 自述文本。
 */
public record PendingActionSummaryView(
        long id,
        String actionType,
        String approvalStatus,
        Integer targetCount,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        OffsetDateTime decidedAt,
        String cancelReason) {
}
