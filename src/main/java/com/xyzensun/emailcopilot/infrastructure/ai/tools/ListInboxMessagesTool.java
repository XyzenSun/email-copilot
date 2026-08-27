package com.xyzensun.emailcopilot.infrastructure.ai.tools;

import com.xyzensun.emailcopilot.application.mail.MailReadApplicationService;
import com.xyzensun.emailcopilot.application.mail.model.MessageListQuery;
import com.xyzensun.emailcopilot.application.mail.model.MessagePageView;
import com.xyzensun.emailcopilot.application.mail.model.MessageSummaryView;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 对话 AI 的收件箱分页浏览工具。
 *
 * <p>复用 {@link MailReadApplicationService#listMessages}（与前端收件箱列表同一查询路径），
 * 让 AI 能回答"今天收到了哪些邮件？总结一下"这类按时间浏览的需求。区别于
 * {@code search_messages}（按关键词相关性检索），本工具按时间范围分页列出收件箱，
 * direction 固定 INBOUND。
 *
 * <p><b>不记 evidence</b>：本工具只给邮件元数据概览让 AI 总结，不涉及 AI 引用具体邮件
 * 正文做主张的深度读取——用户要看详情时 AI 调 {@code read_message}（记 DIRECT_READ）。
 * 只读概览工具的审计不重要（用户决策 2026-08-25）。
 *
 * <p><b>最小暴露</b>：只返 messageId/fromAddress/subject/receivedAt/snippet/hasAttachment，
 * 不返 recipients/category/tags/dkimPassed/threadId（需时 read_message）。
 */
@Component
public class ListInboxMessagesTool {

    /** 单页上限：防止 AI 拉太多邮件摘要塞爆上下文。 */
    private static final int MAX_PAGE_SIZE = 50;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MailReadApplicationService mailReadApplicationService;

    public ListInboxMessagesTool(MailReadApplicationService mailReadApplicationService) {
        this.mailReadApplicationService = mailReadApplicationService;
    }

    @Tool(name = "list_inbox_messages", description = """
            分页列出收件箱（已收到的）邮件。适用于用户问"今天/最近收到了哪些邮件"时浏览收件箱概览。
            按收到时间倒序，只给摘要字段；需要完整正文用 read_message。
            可用 receivedAfter/receivedBefore 按时间范围过滤（ISO 8601 带时区偏移，如 2026-08-25T00:00:00+08:00）。
            """)
    public String listInboxMessages(
            @ToolParam(description = "页码，从 1 开始，默认 1", required = false) Integer page,
            @ToolParam(description = "每页数量，默认 20，上限 50", required = false) Integer size,
            @ToolParam(description = "可选：限定邮箱账号 id，null 表示全部账号", required = false) Long accountId,
            @ToolParam(description = "可选：只列此时间之后收到的邮件。ISO 8601 带时区偏移，如 2026-08-25T00:00:00+08:00", required = false) OffsetDateTime receivedAfter,
            @ToolParam(description = "可选：只列此时间之前收到的邮件。ISO 8601 带时区偏移", required = false) OffsetDateTime receivedBefore) {

        int pageNum = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? 20 : Math.min(size, MAX_PAGE_SIZE);

        MessageListQuery query = new MessageListQuery(
                pageNum,
                pageSize,
                accountId,
                null,                       // category 不过滤
                null,                       // tagId 不过滤
                MessageListQuery.DirectionSelection.INBOUND,  // 收件箱语义
                receivedAfter,
                receivedBefore,
                false);                     // 不含垃圾邮件

        MessagePageView pageView = mailReadApplicationService.listMessages(query);
        List<MessageSummaryView> items = pageView.items();

        if (items.isEmpty()) {
            return "收件箱没有符合条件的邮件。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(pageView.total()).append(" 封收件箱邮件（第 ")
                .append(pageView.page()).append(" 页，每页 ").append(pageView.size()).append(" 封）：\n");
        for (MessageSummaryView m : items) {
            sb.append("- messageId: ").append(m.id());
            sb.append(" | 发件人: ");
            if (m.fromDisplay() != null && !m.fromDisplay().isBlank()) {
                sb.append(m.fromDisplay()).append(" <").append(m.fromAddress()).append('>');
            } else {
                sb.append(m.fromAddress());
            }
            sb.append(" | 主题: ").append(m.subject() != null ? m.subject() : "(无主题)");
            sb.append(" | 时间: ").append(m.receivedAt() != null ? m.receivedAt().format(TIME_FMT) : "未知");
            sb.append(" | 附件: ").append(m.hasAttachment() ? "有" : "无");
            sb.append('\n');
            if (m.snippet() != null && !m.snippet().isBlank()) {
                sb.append("  摘要: ").append(m.snippet()).append('\n');
            }
        }
        return sb.toString();
    }
}
