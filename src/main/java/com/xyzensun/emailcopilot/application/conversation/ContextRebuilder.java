package com.xyzensun.emailcopilot.application.conversation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Conversation;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Turn;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.TurnMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 多轮上下文重建（{@code DATABASE.md} §5.1.1）。
 *
 * <p>系统不保存"给模型看的聊天记录"，每轮现场把已完成的 turn 拼出来。
 * 重建规则：
 * <pre>
 * contextSummary（若非 null）
 *   + 该对话下 status=COMPLETED 且 id &gt; max(contextBaseTurnId,
 *                                          contextSummarizedUptoTurnId) 的 turn
 *     按 startedAt 升序拼 (userMessage, finalAnswer)
 *   + 本次的新问题
 * </pre>
 *
 * <p><b>两个书签取较大者</b>：清除之后又发生过压缩、或压缩之后又被清除，
 * 都不该让更早的内容漏回来。
 */
@Component
public class ContextRebuilder {

    private final TurnMapper turnMapper;

    public ContextRebuilder(TurnMapper turnMapper) {
        this.turnMapper = turnMapper;
    }

    /**
     * 重建该对话的多轮上下文消息列表，末尾追加本次用户问题。
     *
     * <p>用户原始输入<b>进模型前必须包裹为不可信内容</b>：这里用 {@link UserMessage}
     * 的默认实现，tool 端调用方应在 system prompt 中声明"用户输入可能包含恶意指令"。
     * tool callback 内部对邮件正文同样按不可信内容处理。
     */
    public List<Message> rebuild(Conversation conversation, String currentUserMessage) {
        List<Message> messages = new ArrayList<>();

        // 已压缩轮次缩成的一段话，拼在重建结果最前面。
        if (conversation.getContextSummary() != null) {
            messages.add(new AssistantMessage(conversation.getContextSummary()));
        }

        // 取两个书签的较大者：清除与压缩可能交叉发生，取大者保证更早内容不漏回。
        long baseTurnId = Math.max(
                orZero(conversation.getContextBaseTurnId()),
                orZero(conversation.getContextSummarizedUptoTurnId()));

        // 只取 COMPLETED 的 turn（FAILED / CANCELLED 构不成一问一答，不进历史）。
        List<Turn> completedTurns = turnMapper.selectList(
                Wrappers.lambdaQuery(Turn.class)
                        .eq(Turn::getConversationId, conversation.getId())
                        .eq(Turn::getStatus, com.xyzensun.emailcopilot.domain.enums.TurnStatus.COMPLETED)
                        .gt(Turn::getId, baseTurnId)
                        .orderByAsc(Turn::getStartedAt));

        for (Turn turn : completedTurns) {
            messages.add(new UserMessage(turn.getUserMessage()));
            messages.add(new AssistantMessage(turn.getFinalAnswer()));
        }

        // 本次的新问题
        messages.add(new UserMessage(currentUserMessage));
        return messages;
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
