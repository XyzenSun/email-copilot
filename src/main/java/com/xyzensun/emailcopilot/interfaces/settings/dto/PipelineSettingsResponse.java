package com.xyzensun.emailcopilot.interfaces.settings.dto;

import com.xyzensun.emailcopilot.application.settings.PipelineSettings;

import java.math.BigDecimal;

/** {@code GET/PATCH /api/settings/pipeline} 的响应。 */
public record PipelineSettingsResponse(
        boolean spamCheckEnabled,
        BigDecimal spamClassificationThreshold,
        String spamJudgmentPrompt,
        boolean classifyEnabled,
        boolean taggingEnabled,
        boolean languageTranslationEnabled,
        boolean summaryEnabled,
        boolean threadSummaryEnabled) {

    @Override
    public String toString() {
        return "PipelineSettingsResponse[spamCheckEnabled=" + spamCheckEnabled
                + ", spamClassificationThreshold=" + spamClassificationThreshold
                + ", spamJudgmentPrompt=<已隐藏>"
                + ", classifyEnabled=" + classifyEnabled
                + ", taggingEnabled=" + taggingEnabled
                + ", languageTranslationEnabled=" + languageTranslationEnabled
                + ", summaryEnabled=" + summaryEnabled
                + ", threadSummaryEnabled=" + threadSummaryEnabled + "]";
    }

    public static PipelineSettingsResponse from(PipelineSettings settings) {
        return new PipelineSettingsResponse(
                settings.spamCheckEnabled(),
                settings.spamClassificationThreshold(),
                settings.spamJudgmentPrompt(),
                settings.classifyEnabled(),
                settings.taggingEnabled(),
                settings.languageTranslationEnabled(),
                settings.summaryEnabled(),
                settings.threadSummaryEnabled());
    }
}
