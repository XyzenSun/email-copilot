package com.xyzensun.emailcopilot.interfaces.draft.dto;

import com.xyzensun.emailcopilot.domain.Recipients;

/** 新建草稿请求（openapi {@code DraftCreateRequest}）。subject/bodyText/recipients 允许空值。 */
public record DraftCreateRequest(
        long fromMailAccountId,
        Long conversationId,
        Long inReplyToMessageId,
        Recipients recipients,
        String subject,
        String bodyText) {
}
