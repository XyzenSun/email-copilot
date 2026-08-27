package com.xyzensun.emailcopilot.infrastructure.ai.tools;

import com.xyzensun.emailcopilot.application.conversation.TurnEventSink;
import com.xyzensun.emailcopilot.application.mail.MailSearchService;
import com.xyzensun.emailcopilot.application.mail.model.MailSearchQuery;
import com.xyzensun.emailcopilot.domain.enums.EvidenceSource;
import com.xyzensun.emailcopilot.domain.enums.EvidenceTargetType;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.TurnReadEvidenceMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 对话 AI 的本地邮件检索工具（design.md §4.1）。
 *
 * <p>调用 {@link MailSearchService#relevanceSearch}（阶段 6 已完整实现），返回 BM25 候选
 * 经 PostgreSQL 权威复核后的结果。evidence.source = {@code RELEVANCE_SEARCH}。
 *
 * <p>结果只给模型 messageId、matchedField、snippet，不直接给正文——正文由 read_message 工具按需读取。
 * 这避免一次性把所有命中邮件正文塞进上下文。
 */
@Component
public class SearchMessagesTool {

    private final MailSearchService mailSearchService;
    private final TurnReadEvidenceMapper evidenceMapper;

    public SearchMessagesTool(MailSearchService mailSearchService, TurnReadEvidenceMapper evidenceMapper) {
        this.mailSearchService = mailSearchService;
        this.evidenceMapper = evidenceMapper;
    }

    @Tool(name = "search_messages", description = """
            在本地邮件中按关键词检索。返回匹配邮件的 id、匹配字段和文本摘要。
            适用于用户询问"有没有关于 X 的邮件"时定位候选邮件。
            检索结果只给摘要，需要完整正文时用 read_message。
            """)
    public String searchMessages(
            @ToolParam(description = "自然语言或关键词检索词") String query,
            @ToolParam(description = "可选：限定邮箱账号 id，null 表示全部账号", required = false) Long accountId,
            @ToolParam(description = "可选：是否包含垃圾邮件，默认 false", required = false) Boolean includeSpam,
            ToolContext toolContext) {
        Long turnId = (Long) toolContext.getContext().get("turnId");
        TurnEventSink sink = (TurnEventSink) toolContext.getContext().get("eventSink");
        MailSearchQuery.RelevanceQuery searchQuery = new MailSearchQuery.RelevanceQuery(
                query,
                accountId,
                MailSearchQuery.DirectionSelection.ALL,
                null,
                null,
                includeSpam != null && includeSpam);
        List<MailSearchQuery.RelevanceResult> results = mailSearchService.relevanceSearch(searchQuery);

        // 记录读取证据：由代码写入，不由 AI 自述。紧挨着前推 SSE evidence 事件——
        // subject/fromAddress/receivedAt 来自检索时已加载的 Message（经 RelevanceResult 传出）。
        for (MailSearchQuery.RelevanceResult result : results) {
            if (turnId != null) {
                evidenceMapper.upsertSource(turnId, EvidenceTargetType.MESSAGE,
                        result.messageId(), EvidenceSource.RELEVANCE_SEARCH);
            }
            if (sink != null) {
                sink.sendEvidence(EvidenceTargetType.MESSAGE, result.messageId(),
                        EvidenceSource.RELEVANCE_SEARCH,
                        result.subject(), result.fromAddress(), result.receivedAt());
            }
        }

        if (results.isEmpty()) {
            return "没有找到匹配的邮件。";
        }
        StringBuilder sb = new StringBuilder("找到 " + results.size() + " 封匹配邮件：\n");
        for (MailSearchQuery.RelevanceResult result : results) {
            sb.append("- messageId: ").append(result.messageId());
            sb.append(" (匹配字段: ").append(result.matchedField()).append(")\n");
            sb.append("  摘要: ").append(result.snippet()).append("\n");
        }
        return sb.toString();
    }
}
