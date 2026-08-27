package com.xyzensun.emailcopilot.application.settings.model;

import java.time.OffsetDateTime;
import java.util.List;

/** 邮箱账号配置、凭据存在性与影响面计数的组合视图。 */
public record MailAccountView(
        Long id,
        String emailAddress,
        String displayName,
        String imapHost,
        Integer imapPort,
        String imapUsername,
        List<String> imapFolders,
        boolean imapEnabled,
        String smtpHost,
        Integer smtpPort,
        String smtpUsername,
        boolean smtpEnabled,
        boolean imapPasswordConfigured,
        boolean smtpPasswordConfigured,
        long messageCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
