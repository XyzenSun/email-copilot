package com.xyzensun.emailcopilot.application.mail.model;

import com.xyzensun.emailcopilot.domain.enums.MessageCategory;

import java.time.OffsetDateTime;

/** 用户关键词检索的完整边界参数。 */
public record MailSearchQuery(
        String queryText,
        SearchField field,
        SortOrder order,
        Long accountId,
        MessageCategory category,
        Long tagId,
        DirectionSelection direction,
        OffsetDateTime receivedAfter,
        OffsetDateTime receivedBefore,
        Boolean hasAttachment,
        boolean includeSpam,
        int page,
        int size) {

    public enum SearchField {
        ANY,
        BODY,
        SUBJECT,
        SENDER
    }

    public enum SortOrder {
        DESC,
        ASC
    }

    public enum DirectionSelection {
        INBOUND,
        OUTBOUND,
        ALL
    }

    /** 对话 AI 的 BM25 候选查询；结果数量由 searchResultLimit 控制。 */
    public record RelevanceQuery(
            String queryText,
            Long accountId,
            MessageCategory category,
            Long tagId,
            DirectionSelection direction,
            OffsetDateTime receivedAfter,
            OffsetDateTime receivedBefore,
            Boolean hasAttachment,
            boolean includeSpam) {

        public RelevanceQuery(
                String queryText,
                Long accountId,
                DirectionSelection direction,
                OffsetDateTime receivedAfter,
                OffsetDateTime receivedBefore,
                boolean includeSpam) {
            this(
                    queryText,
                    accountId,
                    null,
                    null,
                    direction,
                    receivedAfter,
                    receivedBefore,
                    null,
                    includeSpam);
        }
    }

    /**
     * 对话 AI 检索单条结果。{@code subject}/{@code fromAddress}/{@code receivedAt} 供 SSE
     * {@code event: evidence} 富化（openapi ReadEvidence 6 字段）——这些字段在检索时已随
     * {@code Message} 实体加载，顺带传出避免 SSE 出口重复查库。
     */
    public record RelevanceResult(
            long messageId,
            String matchedField,
            String snippet,
            String subject,
            String fromAddress,
            OffsetDateTime receivedAt) {
    }
}
