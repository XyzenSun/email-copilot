package com.xyzensun.emailcopilot.application.conversation;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xyzensun.emailcopilot.application.conversation.model.ConversationDetailView;
import com.xyzensun.emailcopilot.application.conversation.model.ConversationSummaryView;
import com.xyzensun.emailcopilot.application.conversation.model.TurnView;
import com.xyzensun.emailcopilot.domain.enums.ApprovalStatus;
import com.xyzensun.emailcopilot.domain.enums.TurnStatus;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Conversation;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.PendingAction;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Turn;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.ConversationMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.PendingActionMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.TurnMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.TurnReadEvidenceMapper;
import com.xyzensun.emailcopilot.interfaces.error.ApiError;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 对话 CRUD 与上下文管理（{@code API.md} §12.x，design.md §2.2）。
 *
 * <p><b>清除上下文是记书签，不是删数据</b>：{@code contextBaseTurnId} 记下当时最后一轮的 id，
 * 重建只从它之后开始，turn 一行不动——用户还要往回翻聊天记录，删掉就没了。
 *
 * <p><b>删除对话保留 pending_action</b>：对话可被物理删除（无 deleted_at），删除时连带删
 * turn 与 turn_read_evidence，但保留全部 pending_action、pending_action_content 与
 * action_execution——后两张表是系统里唯一记着"用户发过哪些邮件"的地方（对邮箱服务器只读，
 * 发出去的信不保证能读回来）。未决提案在对话删除时转 {@code cancelled}。
 */
@Service
public class ConversationApplicationService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int TITLE_MAX_LENGTH = 200;

    private final ConversationMapper conversationMapper;
    private final TurnMapper turnMapper;
    private final TurnReadEvidenceMapper evidenceMapper;
    private final PendingActionMapper pendingActionMapper;

    public ConversationApplicationService(
            ConversationMapper conversationMapper,
            TurnMapper turnMapper,
            TurnReadEvidenceMapper evidenceMapper,
            PendingActionMapper pendingActionMapper) {
        this.conversationMapper = conversationMapper;
        this.turnMapper = turnMapper;
        this.evidenceMapper = evidenceMapper;
        this.pendingActionMapper = pendingActionMapper;
    }

    @Transactional(readOnly = true)
    public List<ConversationSummaryView> listConversations(
            Integer page, Integer size, Boolean archived) {
        int resolvedPage = page == null ? 1 : page;
        int resolvedSize = size == null ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        if (resolvedPage < 1) {
            resolvedPage = 1;
        }
        var wrapper = Wrappers.lambdaQuery(Conversation.class)
                .orderByDesc(Conversation::getUpdatedAt);
        if (archived != null) {
            wrapper.eq(Conversation::getArchived, archived);
        }
        int offset = (resolvedPage - 1) * resolvedSize;
        wrapper.last("limit " + resolvedSize + " offset " + offset);
        return conversationMapper.selectList(wrapper).stream()
                .map(c -> new ConversationSummaryView(
                        c.getId(), c.getTitle(),
                        Boolean.TRUE.equals(c.getArchived()),
                        c.getUpdatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationDetailView getConversation(long conversationId) {
        Conversation conversation = requireConversation(conversationId);
        List<Turn> turns = turnMapper.selectList(
                Wrappers.lambdaQuery(Turn.class)
                        .eq(Turn::getConversationId, conversationId)
                        .orderByAsc(Turn::getStartedAt));
        List<TurnView> turnViews = turns.stream()
                .map(this::toTurnView)
                .toList();
        return new ConversationDetailView(
                conversation.getId(),
                conversation.getTitle(),
                Boolean.TRUE.equals(conversation.getArchived()),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt(),
                turnViews);
    }

    @Transactional
    public ConversationDetailView patchConversation(
            long conversationId, String title, Boolean archived) {
        Conversation conversation = requireConversation(conversationId);
        LambdaUpdateWrapper<Conversation> update = Wrappers.lambdaUpdate(Conversation.class)
                .eq(Conversation::getId, conversationId);
        if (title != null) {
            if (title.isBlank() || title.length() > TITLE_MAX_LENGTH) {
                throw ApiException.validationFailed(java.util.List.of(
                        new com.xyzensun.emailcopilot.interfaces.error.ValidationErrorItem(
                                "title", "标题不能为空且不超过 " + TITLE_MAX_LENGTH + " 字符")));
            }
            update.set(Conversation::getTitle, title.strip());
        }
        if (archived != null) {
            update.set(Conversation::getArchived, archived);
        }
        update.setSql("updated_at = now()");
        conversationMapper.update(null, update);
        return getConversation(conversationId);
    }

    @Transactional
    public void deleteConversation(long conversationId) {
        Conversation conversation = requireConversation(conversationId);
        // running turn 阻止删除：防止删除进行中的对话导致丢失正在生成的回答。
        long runningTurns = turnMapper.selectCount(
                Wrappers.lambdaQuery(Turn.class)
                        .eq(Turn::getConversationId, conversationId)
                        .eq(Turn::getStatus, TurnStatus.RUNNING));
        if (runningTurns > 0) {
            throw new ApiException(ApiError.TURN_ALREADY_RUNNING);
        }

        // 未决提案转 cancelled（所属对话已删除），保留行以追溯历史。
        // 用 turn_id 反查所属对话的 pending_action：pending_action.turn_id 逻辑引用 turn，
        // turn.conversation_id 逻辑引用 conversation。
        List<Long> turnIds = turnMapper.selectList(
                Wrappers.lambdaQuery(Turn.class)
                        .select(Turn::getId)
                        .eq(Turn::getConversationId, conversationId))
                .stream().map(Turn::getId).toList();
        if (!turnIds.isEmpty()) {
            pendingActionMapper.update(null,
                    Wrappers.lambdaUpdate(PendingAction.class)
                            .in(PendingAction::getTurnId, turnIds)
                            .eq(PendingAction::getApprovalStatus, ApprovalStatus.PENDING)
                            .set(PendingAction::getApprovalStatus, ApprovalStatus.CANCELLED)
                            .set(PendingAction::getCancelReason, "所属对话已删除")
                            .set(PendingAction::getDecidedAt, OffsetDateTime.now()));
        }

        // 连带删 turn 与 turn_read_evidence，保留 pending_action/content/execution。
        if (!turnIds.isEmpty()) {
            evidenceMapper.delete(
                    Wrappers.lambdaQuery(com.xyzensun.emailcopilot.infrastructure.persistence.entity.TurnReadEvidence.class)
                            .in(com.xyzensun.emailcopilot.infrastructure.persistence.entity.TurnReadEvidence::getTurnId, turnIds));
        }
        turnMapper.delete(
                Wrappers.lambdaQuery(Turn.class)
                        .eq(Turn::getConversationId, conversationId));
        conversationMapper.deleteById(conversationId);
    }

    @Transactional
    public void clearContext(long conversationId) {
        Conversation conversation = requireConversation(conversationId);
        // running turn 时 409：清除上下文是用户意图，但 running turn 的上下文还在变。
        long runningTurns = turnMapper.selectCount(
                Wrappers.lambdaQuery(Turn.class)
                        .eq(Turn::getConversationId, conversationId)
                        .eq(Turn::getStatus, TurnStatus.RUNNING));
        if (runningTurns > 0) {
            throw new ApiException(ApiError.TURN_ALREADY_RUNNING);
        }

        // 记书签：当前最大 turn id。重建只从它之后开始，turn 一行不删。
        Long maxTurnId = turnMapper.selectList(
                Wrappers.lambdaQuery(Turn.class)
                        .select(Turn::getId)
                        .eq(Turn::getConversationId, conversationId)
                        .orderByDesc(Turn::getId)
                        .last("limit 1"))
                .stream().map(Turn::getId).findFirst().orElse(null);

        LambdaUpdateWrapper<Conversation> update = Wrappers.lambdaUpdate(Conversation.class)
                .eq(Conversation::getId, conversationId)
                .set(Conversation::getContextBaseTurnId, maxTurnId)
                .setSql("updated_at = now()");
        conversationMapper.update(null, update);
    }

    private Conversation requireConversation(long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new ApiException(ApiError.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    private TurnView toTurnView(Turn turn) {
        return new TurnView(
                turn.getId(),
                turn.getStatus() != null ? turn.getStatus().getValue() : null,
                turn.getUserMessage(),
                turn.getFinalAnswer(),
                turn.getModelCallCount(),
                turn.getStartedAt(),
                turn.getFinishedAt());
    }
}
