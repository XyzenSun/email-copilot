package com.xyzensun.emailcopilot.interfaces.mail.dto;

import com.xyzensun.emailcopilot.application.mail.model.MessageDetailView;
import com.xyzensun.emailcopilot.application.mail.model.MessageSummaryView;
import com.xyzensun.emailcopilot.domain.Recipients;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** OpenAPI MessageDetail 的扁平响应；不把内部 summary 组合对象暴露给前端。 */
public record MessageDetailResponse(
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
        Boolean dkimPassed,
        String bodyText,
        String translatedBody,
        String summary,
        OffsetDateTime sentAt,
        String fromAuthenticatedDomain,
        BigDecimal spamScore,
        List<AttachmentResponse> attachments) {

    public static MessageDetailResponse from(MessageDetailView view) {
        MessageSummaryView summary = view.summary();
        return new MessageDetailResponse(
                summary.id(), summary.threadId(), summary.mailAccountId(), summary.direction(),
                summary.fromDisplay(), summary.fromAddress(), summary.recipients(), summary.subject(),
                summary.snippet(), summary.receivedAt(), summary.category(), summary.tags(),
                summary.hasAttachment(), summary.dkimPassed(), view.bodyText(), view.translatedBody(),
                view.generatedSummary(), view.sentAt(),
                view.fromAuthenticatedDomain(), view.spamScore(),
                view.attachments().stream().map(AttachmentResponse::from).toList());
    }

    public record AttachmentResponse(
            long id,
            String filename,
            String contentType,
            long sizeBytes) {

        private static AttachmentResponse from(MessageDetailView.AttachmentView view) {
            return new AttachmentResponse(
                    view.id(), view.filename(), view.contentType(), view.sizeBytes());
        }
    }
}
