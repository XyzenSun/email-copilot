package com.xyzensun.emailcopilot.interfaces.settings.dto;

/** 凭据对外唯一允许的表达是“是否已配置”两个布尔值。 */
public record MailAccountSecretsResponse(
        boolean imapPassword,
        boolean smtpPassword) {
}
