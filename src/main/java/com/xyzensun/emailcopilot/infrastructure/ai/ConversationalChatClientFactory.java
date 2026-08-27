package com.xyzensun.emailcopilot.infrastructure.ai;

import com.xyzensun.emailcopilot.infrastructure.ai.tools.ListInboxMessagesTool;
import com.xyzensun.emailcopilot.infrastructure.ai.tools.ListMailAccountsTool;
import com.xyzensun.emailcopilot.infrastructure.ai.tools.LocalDeleteProposalTool;
import com.xyzensun.emailcopilot.infrastructure.ai.tools.ReadMessageTool;
import com.xyzensun.emailcopilot.infrastructure.ai.tools.ReadThreadTool;
import com.xyzensun.emailcopilot.infrastructure.ai.tools.SaveDraftTool;
import com.xyzensun.emailcopilot.infrastructure.ai.tools.SearchMessagesTool;
import com.xyzensun.emailcopilot.infrastructure.ai.tools.SendEmailProposalTool;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.AppSettingMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * 对话 AI 的 ChatClient 构建工厂（design.md §3、§10.2）。
 *
 * <p><b>每次请求时按当前 {@link ChatModelHolder#current()} 引用构建，不缓存</b>，
 * 以承接热替换（§8.5：reload 原子替换引用，正在执行的调用继续用旧引用）。
 *
 * <p>与流水线 AI 隔离（PRD R1.2）：流水线走 {@link PipelineAiClient}（无工具、单次 ChatModel.call），
 * 对话走本工厂构建的 {@code ChatClient}（带 ToolCallingAdvisor）。两者共享底层
 * {@link ChatModelHolder#current()} 作为 {@code ChatModel}，provider/端点/型号/超时/key 五项
 * 热改由 {@code AiSettingsService} + {@code ChatModelHolder.reload} 统一覆盖。
 *
 * <p>{@code current()} 为 null 时走 {@code AI_NOT_CONFIGURED}（与流水线同一路径）。
 *
 * @param toolCallbackProvider MCP 工具回调 provider（Exa allowlisted）；
 *        如果 MCP 未配置或不可用，由 Spring 容器注入空 provider
 */
@Component
public class ConversationalChatClientFactory {

    private final ChatModelHolder chatModelHolder;
    private final AppSettingMapper appSettingMapper;
    private final ToolCallbackProvider mcpToolCallbackProvider;
    private final SearchMessagesTool searchMessagesTool;
    private final ReadMessageTool readMessageTool;
    private final ReadThreadTool readThreadTool;
    private final SendEmailProposalTool sendEmailProposalTool;
    private final SaveDraftTool saveDraftTool;
    private final LocalDeleteProposalTool localDeleteProposalTool;
    private final ListMailAccountsTool listMailAccountsTool;
    private final ListInboxMessagesTool listInboxMessagesTool;

    public ConversationalChatClientFactory(
            ChatModelHolder chatModelHolder,
            AppSettingMapper appSettingMapper,
            Optional<ToolCallbackProvider> mcpToolCallbackProvider,
            SearchMessagesTool searchMessagesTool,
            ReadMessageTool readMessageTool,
            ReadThreadTool readThreadTool,
            SendEmailProposalTool sendEmailProposalTool,
            SaveDraftTool saveDraftTool,
            LocalDeleteProposalTool localDeleteProposalTool,
            ListMailAccountsTool listMailAccountsTool,
            ListInboxMessagesTool listInboxMessagesTool) {
        this.chatModelHolder = chatModelHolder;
        this.appSettingMapper = appSettingMapper;
        this.mcpToolCallbackProvider = mcpToolCallbackProvider.orElse(null);
        this.searchMessagesTool = searchMessagesTool;
        this.readMessageTool = readMessageTool;
        this.readThreadTool = readThreadTool;
        this.sendEmailProposalTool = sendEmailProposalTool;
        this.saveDraftTool = saveDraftTool;
        this.localDeleteProposalTool = localDeleteProposalTool;
        this.listMailAccountsTool = listMailAccountsTool;
        this.listInboxMessagesTool = listInboxMessagesTool;
    }

    /**
     * 构建对话 ChatClient 的请求级构建结果。
     *
     * @return 包含 ChatClient 和本次调用的工具调用护栏管理器的构建结果；
     *         ChatModel 未配置时返回空
     */
    public Optional<BuiltChatClient> build(Set<Long> cancelledTurnIds) {
        ChatModel chatModel = chatModelHolder.current();
        if (chatModel == null) {
            return Optional.empty();
        }

        // turn_model_call_limit 护栏：包装 DefaultToolCallingManager，在 executeToolCalls 入口计数。
        ConversationToolCallingManager toolCallingManager =
                ConversationToolCallingManager.forTurn(appSettingMapper, cancelledTurnIds);

        // 不再传自定义 toolExecutionEligibilityChecker（阶段10 根因修复）：
        // 原来的 ToolCallLimitChecker 误解了 apply 语义，导致 filter 丢弃所有模型响应。
        // 用 Spring AI 默认 checker（apply = chatResponse.hasToolCalls()）。
        ToolCallingAdvisor toolCallingAdvisor = ToolCallingAdvisor.builder()
                .toolCallingManager(toolCallingManager)
                .build();

        ChatClient.Builder builder = ChatClient.builder(chatModel)
                .defaultSystem(ConversationSystemPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(toolCallingAdvisor)
                .defaultTools(
                        searchMessagesTool,
                        readMessageTool,
                        readThreadTool,
                        listMailAccountsTool,
                        listInboxMessagesTool,
                        // propose_send_email 暂时关闭注册（2026-08-24）：发信是不可逆动作，
                        // 改由 AI 建草稿（save_draft 免审批直进草稿箱）后，用户在草稿箱自己 POST /send 发送。
                        // sendEmailProposalTool 类与 approve 的 send_email 执行链代码全部保留，
                        // 未来参考用户意见决定是否恢复 AI 提议发信时，取消下行注释即可。
                        // sendEmailProposalTool,
                        saveDraftTool,
                        localDeleteProposalTool);

        // MCP 工具显式注入（不自动注册）。
        if (mcpToolCallbackProvider != null) {
            builder.defaultToolCallbacks(mcpToolCallbackProvider);
        }

        return Optional.of(new BuiltChatClient(builder.build(), toolCallingManager));
    }

    /**
     * 一次请求的构建结果：ChatClient 实例 + 工具调用护栏管理器。
     */
    public record BuiltChatClient(ChatClient chatClient, ConversationToolCallingManager toolCallingManager) {
    }
}
