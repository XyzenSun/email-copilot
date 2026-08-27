package com.xyzensun.emailcopilot.application.approval.model;

import java.time.OffsetDateTime;

/**
 * 批准/拒绝结果视图，供 Controller 构造 {@code ApprovalResult} 响应。
 *
 * <p>拒绝时 {@code execution} 恒为 null。
 * 批准时 {@code execution.status} 可能是 succeeded/failed/indeterminate——都是 HTTP 200。
 */
public record ApprovalResultView(
        long pendingActionId,
        String approvalStatus,
        OffsetDateTime decidedAt,
        ExecutionView execution) {

    public record ExecutionView(
            String status,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            String resultMessage) {
    }
}
