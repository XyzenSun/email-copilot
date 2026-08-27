package com.xyzensun.emailcopilot.interfaces.settings.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.xyzensun.emailcopilot.application.settings.AppSettingService.PipelinePatch;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * AI 阶段开关的部分更新请求。
 *
 * <p>Boolean 必须使用包装类型：JSON 中的 {@code false} 是合法的显式更新，不能用 primitive
 * 的默认值把它和“字段未传”混在一起；setter 记录字段出现情况，显式 null 则由应用层拒绝。
 */
public final class PipelineSettingsUpdateRequest {

    private Boolean spamCheckEnabled;
    private BigDecimal spamClassificationThreshold;
    private String spamJudgmentPrompt;
    private Boolean classifyEnabled;
    private Boolean taggingEnabled;
    private Boolean languageTranslationEnabled;
    private Boolean summaryEnabled;
    private Boolean threadSummaryEnabled;

    private final Set<String> providedFields = new LinkedHashSet<>();

    public Boolean getSpamCheckEnabled() {
        return spamCheckEnabled;
    }

    public void setSpamCheckEnabled(Boolean spamCheckEnabled) {
        this.spamCheckEnabled = spamCheckEnabled;
        providedFields.add("spamCheckEnabled");
    }

    public BigDecimal getSpamClassificationThreshold() {
        return spamClassificationThreshold;
    }

    public void setSpamClassificationThreshold(BigDecimal spamClassificationThreshold) {
        this.spamClassificationThreshold = spamClassificationThreshold;
        providedFields.add("spamClassificationThreshold");
    }

    public String getSpamJudgmentPrompt() {
        return spamJudgmentPrompt;
    }

    public void setSpamJudgmentPrompt(String spamJudgmentPrompt) {
        this.spamJudgmentPrompt = spamJudgmentPrompt;
        providedFields.add("spamJudgmentPrompt");
    }

    public Boolean getClassifyEnabled() {
        return classifyEnabled;
    }

    public void setClassifyEnabled(Boolean classifyEnabled) {
        this.classifyEnabled = classifyEnabled;
        providedFields.add("classifyEnabled");
    }

    public Boolean getTaggingEnabled() {
        return taggingEnabled;
    }

    public void setTaggingEnabled(Boolean taggingEnabled) {
        this.taggingEnabled = taggingEnabled;
        providedFields.add("taggingEnabled");
    }

    public Boolean getSummaryEnabled() {
        return summaryEnabled;
    }

    public void setSummaryEnabled(Boolean summaryEnabled) {
        this.summaryEnabled = summaryEnabled;
        providedFields.add("summaryEnabled");
    }

    public Boolean getLanguageTranslationEnabled() {
        return languageTranslationEnabled;
    }

    public void setLanguageTranslationEnabled(Boolean languageTranslationEnabled) {
        this.languageTranslationEnabled = languageTranslationEnabled;
        providedFields.add("languageTranslationEnabled");
    }

    public Boolean getThreadSummaryEnabled() {
        return threadSummaryEnabled;
    }

    public void setThreadSummaryEnabled(Boolean threadSummaryEnabled) {
        this.threadSummaryEnabled = threadSummaryEnabled;
        providedFields.add("threadSummaryEnabled");
    }

    @JsonIgnore
    public PipelinePatch toPatch() {
        return new PipelinePatch(
                spamCheckEnabled,
                spamClassificationThreshold,
                spamJudgmentPrompt,
                classifyEnabled,
                taggingEnabled,
                languageTranslationEnabled,
                summaryEnabled,
                threadSummaryEnabled,
                providedFields);
    }

    @Override
    public String toString() {
        return "PipelineSettingsUpdateRequest[providedFields=" + providedFields + "]";
    }
}
