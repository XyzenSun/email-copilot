package com.xyzensun.emailcopilot.interfaces.draft.dto;

import com.xyzensun.emailcopilot.application.draft.model.DraftView;
import com.xyzensun.emailcopilot.domain.Recipients;

import java.time.OffsetDateTime;

/** 草稿响应（openapi {@code Draft}）。 */
public record DraftResponse(
        long id,
        Long conversationId,
        Long inReplyToMessageId,
        String inReplyToSubject,
        long fromMailAccountId,
        Recipients recipients,
        String subject,
        String bodyText,
        OffsetDateTime updatedAt) {

    public static DraftResponse from(DraftView view) {
        return new DraftResponse(
                view.id(),
                view.conversationId(),
                view.inReplyToMessageId(),
                view.inReplyToSubject(),
                view.fromMailAccountId(),
                view.recipients(),
                view.subject(),
                view.bodyText(),
                view.updatedAt());
    }
}
