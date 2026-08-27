package com.xyzensun.emailcopilot.interfaces.settings.dto;

import com.xyzensun.emailcopilot.application.settings.model.SystemSettingsView;

/** {@code GET/PATCH /api/settings/system} 的完整响应。 */
public record SystemSettingsResponse(
        String aiProvider,
        String aiBaseUrl,
        String aiModel,
        int aiContextWindowK,
        int aiTimeoutSeconds,
        boolean aiApiKeyConfigured,
        boolean aiReady,
        boolean masterKeyPresent,
        boolean masterKeyMatchesCiphertext,
        boolean mcpApiKeyConfigured,
        boolean tavilyApiKeyConfigured) {

    public static SystemSettingsResponse from(SystemSettingsView settings) {
        return new SystemSettingsResponse(
                settings.aiProvider().getValue(),
                settings.aiBaseUrl(),
                settings.aiModel(),
                settings.aiContextWindowK(),
                settings.aiTimeoutSeconds(),
                settings.aiApiKeyConfigured(),
                settings.aiReady(),
                settings.masterKeyPresent(),
                settings.masterKeyMatchesCiphertext(),
                settings.mcpApiKeyConfigured(),
                settings.tavilyApiKeyConfigured());
    }
}
