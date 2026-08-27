package com.xyzensun.emailcopilot.application.settings.model;

import java.util.List;

/** 新建邮箱账号的应用层命令；凭据刻意不属于该命令。 */
public record MailAccountCreateCommand(
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
        boolean smtpEnabled) {
}
