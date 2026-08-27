package com.xyzensun.emailcopilot.infrastructure.ai.tools;

import com.xyzensun.emailcopilot.application.conversation.TurnEventSink;
import com.xyzensun.emailcopilot.application.mail.MailReadApplicationService;
import com.xyzensun.emailcopilot.application.mail.model.MessageDetailView;
import com.xyzensun.emailcopilot.application.mail.model.MessageSummaryView;
import com.xyzensun.emailcopilot.domain.enums.EvidenceSource;
import com.xyzensun.emailcopilot.domain.enums.EvidenceTargetType;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.TurnReadEvidenceMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 对话 AI 的单封邮件读取工具（design.md §4.1）。
 *
 * <p>调用 {@link MailReadApplicationService#getMessage}（阶段 4A 已实现），返回纯文本正文。
 * evidence.source = {@code DIRECT_READ}——主动点开是更强的信号，覆盖检索来源。
 */
@Component
public class ReadMessageTool {

    private final MailReadApplicationService mailReadApplicationService;
    private final TurnReadEvidenceMapper evidenceMapper;

    public ReadMessageTool(
            MailReadApplicationService mailReadApplicationService,
            TurnReadEvidenceMapper evidenceMapper) {
        this.mailReadApplicationService = mailReadApplicationService;
        this.evidenceMapper = evidenceMapper;
    }

    @Tool(name = "read_message", description = """
            读取单封邮件的完整纯文本内容。传入 search_messages 返回的 messageId。
            返回正文、发件人、收件人、主题等信息。正文不含 HTML 标记。
            """)
    public String readMessage(
            @ToolParam(description = "search_messages 返回的 messageId") Long messageId,
            ToolContext toolContext) {
        Long turnId = (Long) toolContext.getContext().get("turnId");
        TurnEventSink sink = (TurnEventSink) toolContext.getContext().get("eventSink");
        MessageDetailView detail = mailReadApplicationService.getMessage(messageId);
        MessageSummaryView summary = detail.summary();

        // 记录证据：direct_read 覆盖 source（主动点开是更强的信号）。紧挨着前推 SSE evidence
        // 事件——subject/fromAddress/receivedAt 来自已读取的 MessageSummaryView。
        if (turnId != null) {
            evidenceMapper.upsertSource(turnId, EvidenceTargetType.MESSAGE,
                    messageId, EvidenceSource.DIRECT_READ);
        }
        if (sink != null) {
            sink.sendEvidence(EvidenceTargetType.MESSAGE, messageId, EvidenceSource.DIRECT_READ,
                    summary.subject(), summary.fromAddress(), summary.receivedAt());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("主题: ").append(summary.subject()).append("\n");
        sb.append("发件人: ").append(summary.fromDisplay() != null
                ? summary.fromDisplay() : summary.fromAddress()).append("\n");
        sb.append("收件人: ").append(summary.recipients().to()).append("\n");
        sb.append("方向: ").append(summary.direction()).append("\n");
        sb.append("接收时间: ").append(summary.receivedAt()).append("\n");
        if (detail.generatedSummary() != null) {
            sb.append("摘要: ").append(detail.generatedSummary()).append("\n");
        }
        sb.append("\n--- 正文 ---\n");
        sb.append(detail.bodyText());
        return sb.toString();
    }
}
