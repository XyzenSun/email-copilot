package com.xyzensun.emailcopilot.application.settings;

import com.xyzensun.emailcopilot.infrastructure.persistence.entity.AppSetting;

import java.math.BigDecimal;

/**
 * 五个邮件流水线开关、一个独立会话摘要开关和垃圾评分策略的不可变快照。分类与自动标签各自独立，语言判断与翻译共用一个开关。
 *
 * <p>开关只影响后续处理，不触发历史补跑；快照只负责表达当前配置，具体生效点由流水线读取方决定。
 */
public record PipelineSettings(
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
        return "PipelineSettings[spamCheckEnabled=" + spamCheckEnabled
                + ", spamClassificationThreshold=" + spamClassificationThreshold
                + ", spamJudgmentPrompt=<已隐藏>"
                + ", classifyEnabled=" + classifyEnabled
                + ", taggingEnabled=" + taggingEnabled
                + ", languageTranslationEnabled=" + languageTranslationEnabled
                + ", summaryEnabled=" + summaryEnabled
                + ", threadSummaryEnabled=" + threadSummaryEnabled + "]";
    }

    public static PipelineSettings from(AppSetting setting) {
        return new PipelineSettings(
                Boolean.TRUE.equals(setting.getAiSpamCheckEnabled()),
                setting.getSpamClassificationThreshold(),
                setting.getSpamJudgmentPrompt(),
                Boolean.TRUE.equals(setting.getAiClassifyEnabled()),
                Boolean.TRUE.equals(setting.getAiTaggingEnabled()),
                Boolean.TRUE.equals(setting.getAiLanguageTranslationEnabled()),
                Boolean.TRUE.equals(setting.getAiSummaryEnabled()),
                Boolean.TRUE.equals(setting.getAiThreadSummaryEnabled()));
    }
}
