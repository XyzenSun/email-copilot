package com.xyzensun.emailcopilot.application.settings.model;

import java.util.List;

/** 邮箱账号部分更新命令；每个字段保留“是否出现在 JSON 中”的信息。 */
public record MailAccountUpdateCommand(
        MailAccountPatchValue<String> emailAddress,
        MailAccountPatchValue<String> displayName,
        MailAccountPatchValue<String> imapHost,
        MailAccountPatchValue<Integer> imapPort,
        MailAccountPatchValue<String> imapUsername,
        MailAccountPatchValue<List<String>> imapFolders,
        MailAccountPatchValue<Boolean> imapEnabled,
        MailAccountPatchValue<String> smtpHost,
        MailAccountPatchValue<Integer> smtpPort,
        MailAccountPatchValue<String> smtpUsername,
        MailAccountPatchValue<Boolean> smtpEnabled) {

    public boolean hasAnyField() {
        return emailAddress.present()
                || displayName.present()
                || imapHost.present()
                || imapPort.present()
                || imapUsername.present()
                || imapFolders.present()
                || imapEnabled.present()
                || smtpHost.present()
                || smtpPort.present()
                || smtpUsername.present()
                || smtpEnabled.present();
    }
}
