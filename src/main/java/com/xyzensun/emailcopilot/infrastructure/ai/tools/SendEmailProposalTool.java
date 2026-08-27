package com.xyzensun.emailcopilot.infrastructure.ai.tools;

import com.xyzensun.emailcopilot.application.conversation.PendingActionProposalService;
import com.xyzensun.emailcopilot.application.conversation.TurnEventSink;
import com.xyzensun.emailcopilot.domain.Recipients;
import com.xyzensun.emailcopilot.domain.enums.ActionType;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 发信提案工具（design.md §4.3）。
 *
 * <p><b>只创建 PendingAction + 不可变内容快照，绝不连 SMTP</b>。
 * 提案参数重新经过业务校验，不得相信模型已验证目标地址。
 * 阶段 8 的 approve 端点才执行真实 SMTP 发送。
 *
 * <p>{@code providerToolCallId} 在 Spring AI 2.0.0 实际 API 中需自定义 ToolCallingManager 才能提取
 * （design.md §3.2）。当前实现使用 null：uk_pending_action_tool_call 的 WHERE 子句允许 null，
 * 幂等由 {@code canonical_payload_hash} 兜底键保证——该键正是 spike 实测中真正拦住重复提案的键。
 */
@Component
public class SendEmailProposalTool {

    private final PendingActionProposalService proposalService;

    public SendEmailProposalTool(PendingActionProposalService proposalService) {
        this.proposalService = proposalService;
    }

    @Tool(name = "propose_send_email", description = """
            创建一封发信提案。需要用户审批后才真正发送。
            收件人地址、发信账号、主题和正文都会被应用重新校验。
            不执行 SMTP 发送，只创建待审批提案。
            """)
    public String proposeSendEmail(
            @ToolParam(description = "发信邮箱账号 id") Long fromMailAccountId,
            @ToolParam(description = "收件人邮箱地址列表，至少一个") List<String> to,
            @ToolParam(description = "可选：抄送列表", required = false) List<String> cc,
            @ToolParam(description = "可选：密送列表", required = false) List<String> bcc,
            @ToolParam(description = "可选：回复的邮件 id（真回复时填）", required = false) Long inReplyToMessageId,
            @ToolParam(description = "邮件主题") String subject,
            @ToolParam(description = "邮件纯文本正文") String bodyText,
            ToolContext toolContext) {
        Long turnId = (Long) toolContext.getContext().get("turnId");
        TurnEventSink sink = (TurnEventSink) toolContext.getContext().get("eventSink");
        Recipients recipients = new Recipients(
                to != null ? to : List.of(),
                cc != null ? cc : List.of(),
                bcc != null ? bcc : List.of());

        long proposalId = proposalService.createSendEmailProposal(
                turnId, null, fromMailAccountId, inReplyToMessageId,
                recipients, subject, bodyText);

        // 前推 SSE action 事件（与 save_draft/local_delete 同模式）。本工具当前注释关闭注册
        // （ConversationalChatClientFactory），AI 无法调用；恢复注册时此推送即生效。
        if (sink != null) {
            sink.sendAction(proposalId, ActionType.SEND_EMAIL);
        }
        return "已创建发信提案 (id=" + proposalId + ")。用户审批后才会发送。";
    }
}
