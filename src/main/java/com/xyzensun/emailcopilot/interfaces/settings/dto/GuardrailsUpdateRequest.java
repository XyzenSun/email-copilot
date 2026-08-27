package com.xyzensun.emailcopilot.interfaces.settings.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.xyzensun.emailcopilot.application.settings.AppSettingService.GuardrailPatch;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 护栏参数的部分更新请求。
 *
 * <p>不用 record 的原因是 PATCH 必须知道字段是否真的出现在 JSON 中：null 表示客户端明确提交了
 * 一个无效值，而不是把该字段当成“未传”静默忽略。每个 setter 都记录出现过的字段，保留这个区别。
 */
public final class GuardrailsUpdateRequest {

    private Integer initialSyncDays;
    private Integer threadSizeLimit;
    private Integer processingRetryLimit;
    private Integer searchResultLimit;
    private Integer turnModelCallLimit;
    private Integer turnTimeoutSeconds;
    private Integer pendingActionTtlHours;
    private Integer toolTimeoutSeconds;
    private Integer smtpTimeoutSeconds;
    private Boolean autoSyncEnabled;
    private Integer imapSyncIntervalSeconds;
    private Boolean autoDeleteEnabled;
    private Integer messageRetentionDays;

    private final Set<String> providedFields = new LinkedHashSet<>();

    public Integer getInitialSyncDays() {
        return initialSyncDays;
    }

    public void setInitialSyncDays(Integer initialSyncDays) {
        this.initialSyncDays = initialSyncDays;
        providedFields.add("initialSyncDays");
    }

    public Integer getThreadSizeLimit() {
        return threadSizeLimit;
    }

    public void setThreadSizeLimit(Integer threadSizeLimit) {
        this.threadSizeLimit = threadSizeLimit;
        providedFields.add("threadSizeLimit");
    }

    public Integer getProcessingRetryLimit() {
        return processingRetryLimit;
    }

    public void setProcessingRetryLimit(Integer processingRetryLimit) {
        this.processingRetryLimit = processingRetryLimit;
        providedFields.add("processingRetryLimit");
    }

    public Integer getSearchResultLimit() {
        return searchResultLimit;
    }

    public void setSearchResultLimit(Integer searchResultLimit) {
        this.searchResultLimit = searchResultLimit;
        providedFields.add("searchResultLimit");
    }

    public Integer getTurnModelCallLimit() {
        return turnModelCallLimit;
    }

    public void setTurnModelCallLimit(Integer turnModelCallLimit) {
        this.turnModelCallLimit = turnModelCallLimit;
        providedFields.add("turnModelCallLimit");
    }

    public Integer getTurnTimeoutSeconds() {
        return turnTimeoutSeconds;
    }

    public void setTurnTimeoutSeconds(Integer turnTimeoutSeconds) {
        this.turnTimeoutSeconds = turnTimeoutSeconds;
        providedFields.add("turnTimeoutSeconds");
    }

    public Integer getPendingActionTtlHours() {
        return pendingActionTtlHours;
    }

    public void setPendingActionTtlHours(Integer pendingActionTtlHours) {
        this.pendingActionTtlHours = pendingActionTtlHours;
        providedFields.add("pendingActionTtlHours");
    }

    public Integer getToolTimeoutSeconds() {
        return toolTimeoutSeconds;
    }

    public void setToolTimeoutSeconds(Integer toolTimeoutSeconds) {
        this.toolTimeoutSeconds = toolTimeoutSeconds;
        providedFields.add("toolTimeoutSeconds");
    }

    public Integer getSmtpTimeoutSeconds() {
        return smtpTimeoutSeconds;
    }

    public void setSmtpTimeoutSeconds(Integer smtpTimeoutSeconds) {
        this.smtpTimeoutSeconds = smtpTimeoutSeconds;
        providedFields.add("smtpTimeoutSeconds");
    }

    public Boolean getAutoSyncEnabled() {
        return autoSyncEnabled;
    }

    public void setAutoSyncEnabled(Boolean autoSyncEnabled) {
        this.autoSyncEnabled = autoSyncEnabled;
        providedFields.add("autoSyncEnabled");
    }

    public Integer getImapSyncIntervalSeconds() {
        return imapSyncIntervalSeconds;
    }

    public void setImapSyncIntervalSeconds(Integer imapSyncIntervalSeconds) {
        this.imapSyncIntervalSeconds = imapSyncIntervalSeconds;
        providedFields.add("imapSyncIntervalSeconds");
    }

    public Boolean getAutoDeleteEnabled() {
        return autoDeleteEnabled;
    }

    public void setAutoDeleteEnabled(Boolean autoDeleteEnabled) {
        this.autoDeleteEnabled = autoDeleteEnabled;
        providedFields.add("autoDeleteEnabled");
    }

    public Integer getMessageRetentionDays() {
        return messageRetentionDays;
    }

    public void setMessageRetentionDays(Integer messageRetentionDays) {
        this.messageRetentionDays = messageRetentionDays;
        providedFields.add("messageRetentionDays");
    }

    @JsonIgnore
    public GuardrailPatch toPatch() {
        return new GuardrailPatch(
                initialSyncDays,
                threadSizeLimit,
                processingRetryLimit,
                searchResultLimit,
                turnModelCallLimit,
                turnTimeoutSeconds,
                pendingActionTtlHours,
                toolTimeoutSeconds,
                smtpTimeoutSeconds,
                autoSyncEnabled,
                imapSyncIntervalSeconds,
                autoDeleteEnabled,
                messageRetentionDays,
                providedFields);
    }

    @Override
    public String toString() {
        return "GuardrailsUpdateRequest[providedFields=" + providedFields + "]";
    }
}
