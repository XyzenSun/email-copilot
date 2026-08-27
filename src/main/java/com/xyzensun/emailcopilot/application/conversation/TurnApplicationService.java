package com.xyzensun.emailcopilot.application.conversation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xyzensun.emailcopilot.application.conversation.model.TurnRequest;
import com.xyzensun.emailcopilot.domain.enums.TurnStatus;
import com.xyzensun.emailcopilot.infrastructure.ai.AiNotConfiguredException;
import com.xyzensun.emailcopilot.infrastructure.ai.ChatModelHolder;
import com.xyzensun.emailcopilot.infrastructure.ai.ConversationToolCallingManager;
import com.xyzensun.emailcopilot.infrastructure.ai.ConversationalChatClientFactory;
import com.xyzensun.emailcopilot.infrastructure.ai.TurnCancelledException;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.AppSetting;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Conversation;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Turn;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.AppSettingMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.ConversationMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.TurnMapper;
import com.xyzensun.emailcopilot.interfaces.error.ApiError;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话轮次编排器（design.md §2、§3、§5）。
 *
 * <p>创建 Turn(running) → 重建上下文 → 压缩判断 → 驱动 {@code ChatClient.stream()} →
 * SSE 前推 token/evidence/action → 落库终态。
 *
 * <p><b>同一 Conversation 同时只允许一个 running Turn</b>：由
 * {@code uk_turn_running_per_conversation} 部分唯一索引在数据库层拒绝并发。
 * 并发插入第二个 running turn 抛唯一约束冲突 → {@code TURN_ALREADY_RUNNING}（409）。
 *
 * <p><b>SSE 首事件为 start</b>：前端先拿到 turnId，用户停止才有可靠效果。
 * evidence / action 事件由代码产生（非模型自述）。
 */
@Service
public class TurnApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TurnApplicationService.class);
    private static final short SETTINGS_ROW_ID = 1;
    private static final int TITLE_MAX_CODE_POINTS = 60;

    private final ConversationMapper conversationMapper;
    private final TurnMapper turnMapper;
    private final AppSettingMapper appSettingMapper;
    private final ContextRebuilder contextRebuilder;
    private final ContextCompactor contextCompactor;
    private final ConversationalChatClientFactory chatClientFactory;
    private final ChatModelHolder chatModelHolder;
    private final Clock clock;

    /** 取消集合：turnId -> true。POST /turns/{id}/cancel 写入，流侧读取。 */
    private final Set<Long> cancelledTurnIds = ConcurrentHashMap.newKeySet();

    public TurnApplicationService(
            ConversationMapper conversationMapper,
            TurnMapper turnMapper,
            AppSettingMapper appSettingMapper,
            ContextRebuilder contextRebuilder,
            ContextCompactor contextCompactor,
            ConversationalChatClientFactory chatClientFactory,
            ChatModelHolder chatModelHolder,
            Clock clock) {
        this.conversationMapper = conversationMapper;
        this.turnMapper = turnMapper;
        this.appSettingMapper = appSettingMapper;
        this.contextRebuilder = contextRebuilder;
        this.contextCompactor = contextCompactor;
        this.chatClientFactory = chatClientFactory;
        this.chatModelHolder = chatModelHolder;
        this.clock = clock;
    }

    /**
     * 启动一轮对话并返回 SSE 流。
     *
     * <p>流式实现：先在短事务中创建 conversation/turn，发送 start 事件，
     * 然后在事务外驱动 ChatClient.stream()。远程 AI 调用不参与数据库事务。
     *
     * @throws ApiException 流开始前的失败走普通 HTTP 状态码：
     *         {@code AI_NOT_CONFIGURED}（409）、{@code TURN_ALREADY_RUNNING}（409）
     */
    public SseEmitter startTurn(TurnRequest request) {
        // 1. 构建 ChatClient（在事务外，避免 AI 配置访问在事务中）。
        var builtClient = chatClientFactory.build(cancelledTurnIds)
                .orElseThrow(() -> new ApiException(ApiError.AI_NOT_CONFIGURED));
        ChatClient chatClient = builtClient.chatClient();
        ConversationToolCallingManager toolCallingManager = builtClient.toolCallingManager();

        // 2. 创建/复用 conversation + insert turn(running)。
        Conversation conversation = resolveOrCreateConversation(request);
        Turn turn = createRunningTurn(conversation, request.userMessage());
        long turnId = turn.getId();

        // 3. 创建 SSE emitter。
        SseEmitter emitter = new SseEmitter(0L);

        // 4. 发送 start 事件（首事件不可省略）。
        try {
            emitter.send(SseEmitter.event()
                    .name("start")
                    .data(Map.of(
                            "turnId", turnId,
                            "conversationId", conversation.getId(),
                            "title", conversation.getTitle())));
        } catch (IOException exception) {
            emitter.completeWithError(exception);
            markTurnFailed(turnId, "SSE_START_FAILED");
            return emitter;
        }

        // 5. SSE 事件出口（请求级，包住 emitter）：工具经 ToolContext 取得它前推
        //    evidence/action 事件（design §3.4）。token/compacted/error 事件也走它，统一 emit 路径。
        TurnEventSinkImpl sink = new TurnEventSinkImpl(emitter, turnId, cancelledTurnIds);

        // 6. 重建上下文 + 压缩判断（事务外，只读）。
        List<Message> contextMessages = contextRebuilder.rebuild(conversation, request.userMessage());
        tryCompaction(conversation, contextMessages, sink);

        // 7. 驱动 ChatClient.call()（非流式，事务外同步执行——servlet 线程等 emitter 完成）。
        driveStream(chatClient, contextMessages, turnId, emitter, toolCallingManager, sink);

        return emitter;
    }

    /**
     * 取消一轮对话。立即 204，不等待模型调用完成。
     *
     * <p>取消使 {@code running → cancelled}，只阻止未开始步骤，不撤回已完成的读取（evidence 保留）
     * 和已创建的提案（PendingAction 保留）。停止前已生成的文字保存到 {@code turn.finalAnswer}。
     */
    public void cancelTurn(long turnId) {
        cancelledTurnIds.add(turnId);
    }

    private Conversation resolveOrCreateConversation(TurnRequest request) {
        if (request.conversationId() != null) {
            Conversation existing = conversationMapper.selectById(request.conversationId());
            if (existing == null) {
                throw new ApiException(ApiError.CONVERSATION_NOT_FOUND);
            }
            return existing;
        }
        // conversationId=null 时同请求新建对话。标题截断 userMessage。
        Conversation conversation = new Conversation();
        conversation.setTitle(truncateForTitle(request.userMessage()));
        conversation.setArchived(false);
        conversation.setContextTokens(0);
        conversationMapper.insert(conversation);
        return conversation;
    }

    @Transactional
    public Turn createRunningTurn(Conversation conversation, String userMessage) {
        // 先检查是否有 running turn（提前给出友好错误，避免唯一约束异常噪声）。
        long runningCount = turnMapper.selectCount(
                Wrappers.lambdaQuery(Turn.class)
                        .eq(Turn::getConversationId, conversation.getId())
                        .eq(Turn::getStatus, TurnStatus.RUNNING));
        if (runningCount > 0) {
            throw new ApiException(ApiError.TURN_ALREADY_RUNNING);
        }

        Turn turn = new Turn();
        turn.setConversationId(conversation.getId());
        turn.setUserMessage(userMessage);
        turn.setStatus(TurnStatus.RUNNING);
        turn.setModelCallCount(0);
        turn.setStartedAt(OffsetDateTime.now(clock));
        try {
            turnMapper.insert(turn);
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            // 并发下唯一约束兜底：两个请求同时通过 selectCount 后只有一个 insert 成功。
            throw new ApiException(ApiError.TURN_ALREADY_RUNNING);
        }
        return turn;
    }

    private void tryCompaction(
            Conversation conversation, List<Message> contextMessages, TurnEventSink sink) {
        if (!contextCompactor.shouldCompact(conversation, contextMessages)) {
            return;
        }
        ChatModel chatModel = chatModelHolder.current();
        if (chatModel == null) {
            return;
        }
        contextCompactor.compact(chatModel, contextMessages).ifPresent(summary -> {
            contextCompactor.persistCompaction(conversation, summary);
            // compactedTurnCount/usedTokens 暂为占位 0（压缩指标的计算不在本 SSE 项范围）。
            sink.sendCompacted(0, 0);
        });
    }

    /**
     * 驱动 ChatClient.call()（非流式），前推 token 事件，落库终态。
     *
     * <p>非流式（2026-08-25）：解决 deepseek-v4-flash 等模型流式 tool call 与 Spring AI
     * MessageAggregator 聚合不兼容（toolName 丢失）的问题。完整 answer 一次性推 token 事件。
     * 调用线程同步执行——SseEmitter 的底层 servlet 线程等待 emitter 完成。
     */
    private void driveStream(
            ChatClient chatClient,
            List<Message> contextMessages,
            long turnId,
            SseEmitter emitter,
            ConversationToolCallingManager toolCallingManager,
            TurnEventSink sink) {
        // eventSink 经 ToolContext 流到工具方法（research §3.1/§3.3）——工具紧挨写 DB 调用前推
        // evidence/action 事件。Spring AI 的 buildToolContext 读 ToolCallingChatOptions.getToolContext()，
        // 放进 ToolContext 的 Map，一路传到 @Tool 方法的 ToolContext 形参。
        Prompt prompt = new Prompt(contextMessages,
                org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
                        .toolContext(Map.of("turnId", turnId, "eventSink", sink))
                        .build());

        try {
            // 非流式调用（2026-08-25）：deepseek-v4-flash 等模型流式 tool call 分块与
            // Spring AI 的 MessageAggregator 聚合不兼容（toolName 在聚合中丢失致 turn failed，
            // 见 rss-model-tool-call-incompatible）。非流式 .call() 解析完整 ChatResponse，
            // toolName 不经聚合器故不丢失，ToolCallingAdvisor 非流式分支照样驱动 tool call 循环。
            // 代价：token 失去逐字流式（完整 answer 一次性推送）；单次 LLM 生成无法中途取消，
            // 仅在工具循环间（executeToolCalls 入口）可中断——由 ConversationToolCallingManager 检查取消标志。
            String answer = chatClient.prompt(prompt).call().content();
            // call 返回后检查取消：若 call 期间用户取消（标志已置），保存完整 answer 走取消分支。
            if (cancelledTurnIds.remove(turnId)) {
                handleCancelled(turnId, answer != null ? answer : "", emitter);
                return;
            }
            if (answer != null && !answer.isEmpty()) {
                sink.sendToken(answer);
            }
            handleCompleted(turnId, answer, toolCallingManager, emitter);
        } catch (TurnCancelledException exception) {
            handleCancelled(turnId, "", emitter);
        } catch (Exception exception) {
            handleError(turnId, exception, emitter, sink);
        }
    }

    private void handleCompleted(long turnId, String finalAnswer,
                                  ConversationToolCallingManager toolCallingManager, SseEmitter emitter) {
        updateTurnTerminal(turnId, TurnStatus.COMPLETED, finalAnswer, null);
        try {
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(Map.of(
                            "turnId", turnId,
                            "status", "completed",
                            "usedTokens", toolCallingManager.getCallCount())));
        } catch (IOException exception) {
            log.debug("发送 done 事件失败", exception);
        }
        emitter.complete();
    }

    private void handleCancelled(long turnId, String partialAnswer, SseEmitter emitter) {
        // 取消保存半截回答到 finalAnswer；不撤回已创建的 PendingAction。
        updateTurnTerminal(turnId, TurnStatus.CANCELLED, partialAnswer, null);
        try {
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(Map.of(
                            "turnId", turnId,
                            "status", "cancelled")));
        } catch (IOException exception) {
            log.debug("发送 done(cancelled) 事件失败", exception);
        }
        emitter.complete();
    }

    private void handleError(long turnId, Throwable error, SseEmitter emitter, TurnEventSink sink) {
        String reason = error instanceof AiNotConfiguredException
                ? "AI_NOT_CONFIGURED"
                : error.getClass().getSimpleName();
        markTurnFailed(turnId, reason);
        sink.sendError(503, "AI_PROVIDER_UNAVAILABLE", "AI 服务暂时不可用");
        emitter.complete();
    }

    private void markTurnFailed(long turnId, String reason) {
        updateTurnTerminal(turnId, TurnStatus.FAILED, null, reason);
    }

    private void updateTurnTerminal(
            long turnId, TurnStatus status, String finalAnswer, String failureReason) {
        turnMapper.update(null,
                Wrappers.lambdaUpdate(Turn.class)
                        .eq(Turn::getId, turnId)
                        .set(Turn::getStatus, status)
                        .set(Turn::getFinalAnswer, finalAnswer)
                        .set(Turn::getFailureReason, failureReason)
                        .set(Turn::getFinishedAt, OffsetDateTime.now(clock)));
    }

    private static String truncateForTitle(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "新对话";
        }
        String stripped = userMessage.strip();
        int totalCodePoints = stripped.codePointCount(0, stripped.length());
        if (totalCodePoints <= TITLE_MAX_CODE_POINTS) {
            return stripped;
        }
        int endOffset = stripped.offsetByCodePoints(0, TITLE_MAX_CODE_POINTS);
        return stripped.substring(0, endOffset);
    }
}
