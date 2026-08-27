package com.xyzensun.emailcopilot.application.draft.model;

import com.xyzensun.emailcopilot.domain.Recipients;

import java.time.OffsetDateTime;

/**
 * 草稿视图，含后端 join 的 {@code inReplyToSubject}。
 *
 * <p>{@code inReplyToSubject} 从原邮件的 subject 读取；原邮件已删除时为 null。
 */
public record DraftView(
        long id,
        Long conversationId,
        Long inReplyToMessageId,
        String inReplyToSubject,
        long fromMailAccountId,
        Recipients recipients,
        String subject,
        String bodyText,
        OffsetDateTime updatedAt) {
}
