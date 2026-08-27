package com.xyzensun.emailcopilot.application.settings.model;

import com.xyzensun.emailcopilot.domain.enums.AiProvider;

/** 系统设置接口使用的非敏感只读快照。 */
public record SystemSettingsView(
        AiProvider aiProvider,
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
}
