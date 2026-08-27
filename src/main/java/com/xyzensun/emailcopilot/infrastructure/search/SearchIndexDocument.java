package com.xyzensun.emailcopilot.infrastructure.search;

import com.xyzensun.emailcopilot.domain.enums.MessageCategory;
import com.xyzensun.emailcopilot.domain.enums.MessageDirection;

import java.time.OffsetDateTime;
import java.util.List;

/** 从 PostgreSQL 当前事实构造的一封邮件索引投影。 */
public record SearchIndexDocument(
        long messageId,
        long mailAccountId,
        MessageDirection direction,
        MessageCategory category,
        List<Long> tags,
        OffsetDateTime receivedAt,
        String subject,
        String bodyText,
        String fromDisplay,
        boolean hasAttachment) {

    public SearchIndexDocument {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
