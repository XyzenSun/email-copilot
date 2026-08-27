package com.xyzensun.emailcopilot.application.conversation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xyzensun.emailcopilot.application.conversation.model.PendingActionDetailView;
import com.xyzensun.emailcopilot.application.conversation.model.PendingActionSummaryView;
import com.xyzensun.emailcopilot.domain.enums.ActionType;
import com.xyzensun.emailcopilot.domain.enums.ApprovalStatus;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.PendingAction;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.PendingActionContent;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.ActionExecutionMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.PendingActionContentMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.PendingActionMapper;
import com.xyzensun.emailcopilot.interfaces.error.ApiError;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 待审批提案查询（design.md §9.2）。
 *
 * <p>列表：{@code pending} 按 {@code expires_at} 升序（最紧急的在前），其余按
 * {@code decided_at} 降序。列表项只给 {@code targetCount}，不返回完整 targets。
 *
 * <p>详情：send_email / save_draft 带 content 快照，local_delete 带 targetMessageIds。
 */
@Service
public class PendingActionQueryService {

    private final PendingActionMapper pendingActionMapper;
    private final PendingActionContentMapper contentMapper;
    private final ActionExecutionMapper actionExecutionMapper;

    public PendingActionQueryService(
            PendingActionMapper pendingActionMapper,
            PendingActionContentMapper contentMapper,
            ActionExecutionMapper actionExecutionMapper) {
        this.pendingActionMapper = pendingActionMapper;
        this.contentMapper = contentMapper;
        this.actionExecutionMapper = actionExecutionMapper;
    }

    @Transactional(readOnly = true)
    public List<PendingActionSummaryView> listPendingActions() {
        // pending 加 expires_at > now()：避免清扫窗口间把已过期当未决项显示。
        List<PendingAction> pending = pendingActionMapper.selectList(
                Wrappers.lambdaQuery(PendingAction.class)
                        .eq(PendingAction::getApprovalStatus, ApprovalStatus.PENDING)
                        .gt(PendingAction::getExpiresAt, OffsetDateTime.now())
                        .orderByAsc(PendingAction::getExpiresAt));
        // 已决定按 decided_at 降序
        List<PendingAction> decided = pendingActionMapper.selectList(
                Wrappers.lambdaQuery(PendingAction.class)
                        .ne(PendingAction::getApprovalStatus, ApprovalStatus.PENDING)
                        .orderByDesc(PendingAction::getDecidedAt)
                        .last("limit 100"));
        List<PendingAction> all = new java.util.ArrayList<>(pending);
        all.addAll(decided);
        return all.stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public PendingActionDetailView getPendingAction(long pendingActionId) {
        PendingAction action = pendingActionMapper.selectById(pendingActionId);
        if (action == null) {
            throw new ApiException(ApiError.PENDING_ACTION_NOT_FOUND);
        }

        PendingActionDetailView.ContentSnapshot content = null;
        if (action.getActionType() == ActionType.SEND_EMAIL
                || action.getActionType() == ActionType.SAVE_DRAFT) {
            PendingActionContent contentEntity = contentMapper.selectById(pendingActionId);
            if (contentEntity != null) {
                content = new PendingActionDetailView.ContentSnapshot(
                        contentEntity.getFromMailAccountId(),
                        contentEntity.getInReplyToMessageId(),
                        contentEntity.getRecipients(),
                        contentEntity.getSubject(),
                        contentEntity.getBodyText());
            }
        }

        String executionStatus = null;
        var execution = actionExecutionMapper.selectById(pendingActionId);
        if (execution != null) {
            executionStatus = execution.getStatus() != null
                    ? execution.getStatus().getValue() : null;
        }

        List<Long> targetIds = action.getTargetMessageIds() != null
                ? action.getTargetMessageIds() : List.of();
        return new PendingActionDetailView(
                action.getId(),
                action.getActionType() != null ? action.getActionType().getValue() : null,
                action.getApprovalStatus() != null ? action.getApprovalStatus().getValue() : null,
                targetIds,
                content,
                action.getCreatedAt(),
                action.getExpiresAt(),
                action.getDecidedAt(),
                action.getCancelReason(),
                executionStatus);
    }

    private PendingActionSummaryView toSummary(PendingAction action) {
        int targetCount = action.getTargetMessageIds() != null
                ? action.getTargetMessageIds().size() : 0;
        return new PendingActionSummaryView(
                action.getId(),
                action.getActionType() != null ? action.getActionType().getValue() : null,
                action.getApprovalStatus() != null ? action.getApprovalStatus().getValue() : null,
                targetCount,
                action.getCreatedAt(),
                action.getExpiresAt(),
                action.getDecidedAt(),
                action.getCancelReason());
    }
}
