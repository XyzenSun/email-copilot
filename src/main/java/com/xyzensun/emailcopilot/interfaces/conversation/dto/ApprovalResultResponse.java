package com.xyzensun.emailcopilot.interfaces.conversation.dto;

import com.xyzensun.emailcopilot.application.approval.model.ApprovalResultView;

import java.time.OffsetDateTime;

/**
 * 批准/拒绝结果响应（openapi {@code ApprovalResult}）。
 * 拒绝时 {@code execution} 恒为 null。
 */
public record ApprovalResultResponse(
        long pendingActionId,
        String approvalStatus,
        OffsetDateTime decidedAt,
        ActionExecutionResponse execution) {

    public static ApprovalResultResponse from(ApprovalResultView view) {
        ActionExecutionResponse execution = view.execution() != null
                ? ActionExecutionResponse.from(view.execution()) : null;
        return new ApprovalResultResponse(
                view.pendingActionId(),
                view.approvalStatus(),
                view.decidedAt(),
                execution);
    }

    public record ActionExecutionResponse(
            String status,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            String resultMessage) {

        public static ActionExecutionResponse from(ApprovalResultView.ExecutionView view) {
            return new ActionExecutionResponse(
                    view.status(),
                    view.startedAt(),
                    view.finishedAt(),
                    view.resultMessage());
        }
    }
}
