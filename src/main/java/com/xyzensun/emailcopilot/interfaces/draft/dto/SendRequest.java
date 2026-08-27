package com.xyzensun.emailcopilot.interfaces.draft.dto;

import com.xyzensun.emailcopilot.domain.Recipients;

/** 用户直接发信请求（openapi {@code SendRequest}）。内容取自请求体，不从 draftId 读库。 */
public record SendRequest(
        long fromMailAccountId,
        Long inReplyToMessageId,
        Recipients recipients,
        String subject,
        String bodyText,
        Long draftId) {
}
