package com.xyzensun.emailcopilot.application.mail.model;

import com.xyzensun.emailcopilot.domain.Recipients;

import java.time.OffsetDateTime;
import java.util.List;

/** 邮件列表/会话共用的纯文本摘要视图。 */
public record MessageSummaryView(
        long id,
        long threadId,
        long mailAccountId,
        String direction,
        String fromDisplay,
        String fromAddress,
        Recipients recipients,
        String subject,
        String snippet,
        OffsetDateTime receivedAt,
        String category,
        List<Long> tags,
        boolean hasAttachment,
        Boolean dkimPassed) {
}
