package com.xyzensun.emailcopilot.interfaces.settings.dto;

import com.xyzensun.emailcopilot.application.settings.GuardrailSettings;

/** {@code GET/PATCH /api/settings/guardrails} 的响应。 */
public record GuardrailsResponse(
        int initialSyncDays,
        int threadSizeLimit,
        int processingRetryLimit,
        int searchResultLimit,
        int turnModelCallLimit,
        int turnTimeoutSeconds,
        int pendingActionTtlHours,
        int toolTimeoutSeconds,
        int smtpTimeoutSeconds,
        boolean autoSyncEnabled,
        int imapSyncIntervalSeconds,
        boolean autoDeleteEnabled,
        int messageRetentionDays) {

    public static GuardrailsResponse from(GuardrailSettings settings) {
        return new GuardrailsResponse(
                settings.initialSyncDays(),
                settings.threadSizeLimit(),
                settings.processingRetryLimit(),
                settings.searchResultLimit(),
                settings.turnModelCallLimit(),
                settings.turnTimeoutSeconds(),
                settings.pendingActionTtlHours(),
                settings.toolTimeoutSeconds(),
                settings.smtpTimeoutSeconds(),
                settings.autoSyncEnabled(),
                settings.imapSyncIntervalSeconds(),
                settings.autoDeleteEnabled(),
                settings.messageRetentionDays());
    }
}
