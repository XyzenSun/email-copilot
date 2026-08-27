package com.xyzensun.emailcopilot.infrastructure.ai;

import com.xyzensun.emailcopilot.infrastructure.persistence.entity.AppSetting;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.AppSettingMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单 Turn 工具调用上限的护栏包装（阶段10 design §3.2）。
 *
 * <p><b>为什么需要这个类</b>：阶段10 根因排查（{@code research/tool-call-bug-rootcause.md}）
 * 确认 {@code ToolCallLimitChecker} 严重误解了 {@code ToolExecutionEligibilityChecker.apply}
 * 的语义——{@code apply} 的真实语义是「这个 ChatResponse 是否是 tool call 响应」（被
 * {@code isToolCallResponse} default 方法调用），而非「是否允许执行工具」。语义错位导致
 * Spring AI 的 filter 把所有模型响应（含正常文本 token）当作 tool call 丢弃，AI 呈现空转。
 *
 * <p>修复方案（design §3.1）：不再传自定义 eligibility checker，让 {@link ToolCallingAdvisor}
 * 用 Spring AI 默认 checker（{@code apply = chatResponse.hasToolCalls()}）。{@code turn_model_call_limit}
 * 的计数职责迁移到本类——包装 {@link DefaultToolCallingManager}，在 {@code executeToolCalls}
 * 入口做计数检查。
 *
 * <p><b>为什么不直接继承 {@code DefaultToolCallingManager}</b>：它是 {@code public final class}
 * （反编译确认），且其 {@code Builder} 不存在 {@code maxTotalToolCalls} 方法。包装 executeToolCalls
 * 入口计数是代价最小的方式。
 *
 * <p><b>超限处理</b>：超限不抛异常、不执行工具，返回一个 {@link ToolExecutionResult}，其
 * {@code conversationHistory()} = 原指令 + 模型本次 assistant message + 一条「已达工具调用上限」
 * 的 {@link ToolResponseMessage}（对模型请求的每个 tool call 各回一条 tool response）。
 * 模型拿到这些 tool response 后生成诚实收尾回答 → {@code turn.status = COMPLETED}（非 FAILED）。
 * 这对应 design §3.1 {@code RETURN_ERROR_RESPONSE} 语义。
 *
 * <p><b>取消检查（非流式，2026-08-25）</b>：非流式 {@code call()} 期间单次 LLM 生成无法中断，
 * 但工具循环间——每次 {@code executeToolCalls} 入口——检查 {@code cancelledTurnIds}，命中即抛
 * {@link TurnCancelledException} 中断后续工具。turnId 从 {@code prompt} 的 ToolContext 解析
 * （driveStream 注入）。
 *
 * <p>每个 Turn 创建一个新实例（{@link #forTurn}），计数器从 0 开始。{@link #getCallCount()}
 * 返回<b>实际执行</b>的工具调用数（超限的未执行调用不计入）。
 */
public final class ConversationToolCallingManager implements ToolCallingManager {

    private static final short SETTINGS_ROW_ID = 1;
    private static final int DEFAULT_LIMIT = 10;

    /** 被包装的真实工具调用执行器（执行 + 回灌，由 Spring AI 默认实现保证正确）。 */
    private final ToolCallingManager delegate;
    private final AtomicInteger callCount;
    private final int limit;
    /** 共享的取消标志集（TurnApplicationService 持有，本 manager 持有引用）；命中 turnId 即中断。 */
    private final Set<Long> cancelledTurnIds;

    public ConversationToolCallingManager(int limit) {
        this(DefaultToolCallingManager.builder().build(), limit, java.util.Set.of());
    }

    /** forTurn 用：默认 delegate + 指定 limit + 取消标志集。 */
    public ConversationToolCallingManager(int limit, Set<Long> cancelledTurnIds) {
        this(DefaultToolCallingManager.builder().build(), limit, cancelledTurnIds);
    }

    /**
     * 包注入/测试用构造器：允许指定 delegate（测试时可注入桩 delegate，生产用默认）。
     */
    public ConversationToolCallingManager(ToolCallingManager delegate, int limit, Set<Long> cancelledTurnIds) {
        this.delegate = delegate;
        this.limit = limit;
        this.callCount = new AtomicInteger(0);
        this.cancelledTurnIds = cancelledTurnIds;
    }

    /**
     * 为一个 Turn 创建独立的计数器实例，limit 从 {@code app_setting.turn_model_call_limit} 读，
     * 缺省（行不存在或字段为 null）用默认值 10（与原 {@code ToolCallLimitChecker.forTurn} 同语义）。
     * cancelledTurnIds 由调用方传入（共享引用，cancelTurn 写入即对在途 turn 生效）。
     */
    public static ConversationToolCallingManager forTurn(AppSettingMapper appSettingMapper, Set<Long> cancelledTurnIds) {
        AppSetting settings = appSettingMapper.selectById(SETTINGS_ROW_ID);
        int limit = settings != null && settings.getTurnModelCallLimit() != null
                ? settings.getTurnModelCallLimit() : DEFAULT_LIMIT;
        return new ConversationToolCallingManager(limit, cancelledTurnIds);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        // 非流式取消：工具循环间在此入口检查。单次 LLM 生成期间无法取消（固有限制）。
        Long turnId = extractTurnId(prompt);
        if (turnId != null && cancelledTurnIds.remove(turnId)) {
            throw new TurnCancelledException(turnId);
        }
        // incrementAndGet 原子；超限时回退计数，使 getCallCount() 反映实际执行的工具调用数
        // （与 design §6 验收一致：limit=1 调 2 次工具，model_call_count=1）。
        int current = callCount.incrementAndGet();
        if (current > limit) {
            callCount.decrementAndGet();
            return limitReachedResult(prompt, chatResponse);
        }
        return delegate.executeToolCalls(prompt, chatResponse);
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
        return delegate.resolveToolDefinitions(options);
    }

    public int getCallCount() {
        return callCount.get();
    }

    public int getLimit() {
        return limit;
    }

    /** 从 prompt 的 ToolContext 取 turnId（driveStream 注入 "turnId" → Long）。 */
    private Long extractTurnId(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions opts
                && opts.getToolContext() != null) {
            Object id = opts.getToolContext().get("turnId");
            return id instanceof Long ? (Long) id : null;
        }
        return null;
    }

    /**
     * 构造超限时的 ToolExecutionResult：原指令 + 模型 assistant message + 「已达上限」tool response。
     *
     * <p>镜像 {@code DefaultToolCallingManager.executeToolCalls} 偏移 12-53 的取法
     * （{@code chatResponse.getResults().filter(g -> g.getOutput().hasToolCalls()).findFirst()}）
     * 提取模型本次请求工具调用的 assistant message，再对其中每个 tool call 回一条
     * {@link ToolResponseMessage.ToolResponse}（id 对齐模型请求，使模型能关联）。
     * 回灌结构 {@code instructions + assistantMessage + toolResponseMessage} 与
     * {@code DefaultToolCallingManager.buildConversationHistoryAfterToolExecution} 一致。
     */
    private ToolExecutionResult limitReachedResult(Prompt prompt, ChatResponse chatResponse) {
        List<Message> history = new ArrayList<>(prompt.getInstructions());
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        String limitMessage = "已达到本轮工具调用上限(" + limit + ")，请基于已获取的信息给出最终回答。";

        AssistantMessage assistantMessage = extractAssistantMessageWithToolCalls(chatResponse);
        if (assistantMessage != null) {
            history.add(assistantMessage);
            // 对模型请求的每个 tool call 各回一条 tool response，id 对齐模型请求。
            for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                responses.add(new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolCall.name(), limitMessage));
            }
        } else {
            // 防御：默认 checker 保证走到这里时 chatResponse 有 tool call，此分支理论上不达。
            // 万一发生，仍给一条合成 tool response，让模型收尾而非空转。
            responses.add(new ToolResponseMessage.ToolResponse(
                    "limit_reached", "tool_call_limit", limitMessage));
        }

        history.add(ToolResponseMessage.builder().responses(responses).build());
        return ToolExecutionResult.builder().conversationHistory(history).build();
    }

    /**
     * 从 ChatResponse 中取出含 tool call 的 AssistantMessage（镜像
     * {@code DefaultToolCallingManager.executeToolCalls} 偏移 12-53 的
     * {@code filter(g -> !isEmpty(g.getOutput().getToolCalls())).findFirst()}）。
     */
    private AssistantMessage extractAssistantMessageWithToolCalls(ChatResponse chatResponse) {
        if (chatResponse == null || CollectionUtils.isEmpty(chatResponse.getResults())) {
            return null;
        }
        for (Generation generation : chatResponse.getResults()) {
            AssistantMessage output = generation.getOutput();
            if (output != null && output.hasToolCalls()) {
                return output;
            }
        }
        return null;
    }
}
