package com.xyzensun.emailcopilot.application.settings;

import com.xyzensun.emailcopilot.infrastructure.persistence.entity.AppSetting;

/**
 * 护栏参数的不可变运行时快照。
 *
 * <p>应用层不把持久化实体直接传到接口层：实体是可变的，且包含同一行中的 AI 配置与阶段开关。
 * 将本组字段收敛成快照，既避免调用方意外改动配置，也让后续流水线读取时只依赖它真正需要的参数。
 */
public record GuardrailSettings(
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

    public static GuardrailSettings from(AppSetting setting) {
        return new GuardrailSettings(
                setting.getInitialSyncDays(),
                setting.getThreadSizeLimit(),
                setting.getProcessingRetryLimit(),
                setting.getSearchResultLimit(),
                setting.getTurnModelCallLimit(),
                setting.getTurnTimeoutSeconds(),
                setting.getPendingActionTtlHours(),
                setting.getToolTimeoutSeconds(),
                setting.getSmtpTimeoutSeconds(),
                setting.getAutoSyncEnabled(),
                setting.getImapSyncIntervalSeconds(),
                setting.getAutoDeleteEnabled(),
                setting.getMessageRetentionDays());
    }
}
