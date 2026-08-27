package com.xyzensun.emailcopilot.infrastructure.ai.tools;

import com.xyzensun.emailcopilot.application.conversation.TurnEventSink;
import com.xyzensun.emailcopilot.application.mail.MailReadApplicationService;
import com.xyzensun.emailcopilot.application.mail.model.MessageSummaryView;
import com.xyzensun.emailcopilot.application.mail.model.ThreadDetailView;
import com.xyzensun.emailcopilot.domain.enums.EvidenceSource;
import com.xyzensun.emailcopilot.domain.enums.EvidenceTargetType;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.TurnReadEvidenceMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 对话 AI 的会话读取工具（design.md §4.1）。
 *
 * <p>调用 {@link MailReadApplicationService#getThread}（阶段 4A 已实现），返回会话内所有邮件的摘要。
 * evidence.source = {@code DIRECT_READ}，targetType = {@code THREAD}。
 */
@Component
public class ReadThreadTool {

    private final MailReadApplicationService mailReadApplicationService;
    private final TurnReadEvidenceMapper evidenceMapper;

    public ReadThreadTool(
            MailReadApplicationService mailReadApplicationService,
            TurnReadEvidenceMapper evidenceMapper) {
        this.mailReadApplicationService = mailReadApplicationService;
        this.evidenceMapper = evidenceMapper;
    }

    @Tool(name = "read_thread", description = """
            读取一个邮件会话（thread）内所有邮件的摘要列表。传入 threadId。
            返回每封邮件的 id、主题、发件人和摘要。需要某封邮件的完整正文时用 read_message。
            """)
    public String readThread(
            @ToolParam(description = "邮件会话 id (threadNodeId)") Long threadId,
            ToolContext toolContext) {
        Long turnId = (Long) toolContext.getContext().get("turnId");
        TurnEventSink sink = (TurnEventSink) toolContext.getContext().get("eventSink");
        ThreadDetailView thread = mailReadApplicationService.getThread(threadId);

        // 记录证据：会话级别的读取证据，targetType=THREAD。紧挨着前推 SSE evidence 事件。
        // THREAD 无单一 subject/fromAddress/receivedAt（thread_node 不存这些列），用会话内
        // 最新一封邮件（getThread 按 receivedAt 升序，最后一项即最新）的字段，使证据卡片可点击
        // 跳到会话视图（前端对 subject==null 显示「该邮件已删除」不可点击，故必须带真实 subject）。
        if (turnId != null) {
            evidenceMapper.upsertSource(turnId, EvidenceTargetType.THREAD,
                    threadId, EvidenceSource.DIRECT_READ);
        }
        if (sink != null) {
            List<MessageSummaryView> items = thread.items();
            MessageSummaryView latest = items.get(items.size() - 1);
            sink.sendEvidence(EvidenceTargetType.THREAD, threadId, EvidenceSource.DIRECT_READ,
                    latest.subject(), latest.fromAddress(), latest.receivedAt());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("会话共 ").append(thread.messageCount()).append(" 封邮件：\n");
        for (MessageSummaryView item : thread.items()) {
            sb.append("- messageId: ").append(item.id());
            sb.append(" 主题: ").append(item.subject()).append("\n");
            sb.append("  发件人: ").append(item.fromDisplay() != null
                    ? item.fromDisplay() : item.fromAddress()).append("\n");
            sb.append("  摘要: ").append(item.snippet()).append("\n");
        }
        return sb.toString();
    }
}
