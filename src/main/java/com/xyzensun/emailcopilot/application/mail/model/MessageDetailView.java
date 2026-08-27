package com.xyzensun.emailcopilot.application.mail.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** 单封邮件详情；正文及派生文本在应用层离线清理为纯文本。 */
public record MessageDetailView(
        MessageSummaryView summary,
        String bodyText,
        String translatedBody,
        String generatedSummary,
        OffsetDateTime sentAt,
        String fromAuthenticatedDomain,
        BigDecimal spamScore,
        List<AttachmentView> attachments) {

    public record AttachmentView(long id, String filename, String contentType, long sizeBytes) {
    }
}
