package com.xyzensun.emailcopilot.interfaces.settings.dto;

import com.xyzensun.emailcopilot.application.settings.model.MailAccountView;

import java.time.OffsetDateTime;
import java.util.List;

/** {@code openapi.yaml} 的 {@code MailAccount}。 */
public record MailAccountResponse(
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
        MailAccountSecretsResponse secrets,
        long messageCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static MailAccountResponse from(MailAccountView view) {
        return new MailAccountResponse(
                view.id(),
                view.emailAddress(),
                view.displayName(),
                view.imapHost(),
                view.imapPort(),
                view.imapUsername(),
                view.imapFolders(),
                view.imapEnabled(),
                view.smtpHost(),
                view.smtpPort(),
                view.smtpUsername(),
                view.smtpEnabled(),
                new MailAccountSecretsResponse(
                        view.imapPasswordConfigured(),
                        view.smtpPasswordConfigured()),
                view.messageCount(),
                view.createdAt(),
                view.updatedAt());
    }
}
