package com.xyzensun.emailcopilot.interfaces.settings.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.xyzensun.emailcopilot.application.settings.AiSettingsService.AiSettingsPatch;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * AI 连接配置的部分更新请求。
 *
 * <p>setter 记录字段是否真正出现在 JSON 中，使 {@code aiBaseUrl:null}/{@code aiModel:null}
 * 能表达主动清空，同时把其它必填字段的显式 null 与省略严格区分。
 */
public final class SystemSettingsUpdateRequest {

    private String aiProvider;
    private String aiBaseUrl;
    private String aiModel;
    private Integer aiContextWindowK;
    private Integer aiTimeoutSeconds;
    private final Set<String> providedFields = new LinkedHashSet<>();

    public String getAiProvider() {
        return aiProvider;
    }

    public void setAiProvider(String aiProvider) {
        this.aiProvider = aiProvider;
        providedFields.add("aiProvider");
    }

    public String getAiBaseUrl() {
        return aiBaseUrl;
    }

    public void setAiBaseUrl(String aiBaseUrl) {
        this.aiBaseUrl = aiBaseUrl;
        providedFields.add("aiBaseUrl");
    }

    public String getAiModel() {
        return aiModel;
    }

    public void setAiModel(String aiModel) {
        this.aiModel = aiModel;
        providedFields.add("aiModel");
    }

    public Integer getAiContextWindowK() {
        return aiContextWindowK;
    }

    public void setAiContextWindowK(Integer aiContextWindowK) {
        this.aiContextWindowK = aiContextWindowK;
        providedFields.add("aiContextWindowK");
    }

    public Integer getAiTimeoutSeconds() {
        return aiTimeoutSeconds;
    }

    public void setAiTimeoutSeconds(Integer aiTimeoutSeconds) {
        this.aiTimeoutSeconds = aiTimeoutSeconds;
        providedFields.add("aiTimeoutSeconds");
    }

    @JsonIgnore
    public AiSettingsPatch toPatch() {
        return new AiSettingsPatch(
                aiProvider,
                aiBaseUrl,
                aiModel,
                aiContextWindowK,
                aiTimeoutSeconds,
                providedFields);
    }

    @Override
    public String toString() {
        return "SystemSettingsUpdateRequest[providedFields=" + providedFields + "]";
    }
}
