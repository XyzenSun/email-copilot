package com.xyzensun.emailcopilot.interfaces.mail.dto;

import com.xyzensun.emailcopilot.application.mail.model.MessageSummaryView;
import com.xyzensun.emailcopilot.domain.Recipients;

import java.time.OffsetDateTime;
import java.util.List;

public record MessageSummaryResponse(
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

    public static MessageSummaryResponse from(MessageSummaryView view) {
        return new MessageSummaryResponse(
                view.id(), view.threadId(), view.mailAccountId(), view.direction(),
                view.fromDisplay(), view.fromAddress(), view.recipients(), view.subject(),
                view.snippet(), view.receivedAt(), view.category(), view.tags(),
                view.hasAttachment(), view.dkimPassed());
    }
}
