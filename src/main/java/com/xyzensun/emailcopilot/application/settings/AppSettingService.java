package com.xyzensun.emailcopilot.application.settings;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.AppSetting;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.AppSettingMapper;
import com.xyzensun.emailcopilot.interfaces.error.ApiError;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import com.xyzensun.emailcopilot.interfaces.error.ValidationErrorItem;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 单行 {@code app_setting} 的读写用例。
 *
 * <p>配置更新先在应用层校验，再在同一事务中写入数据库；数据库 check 是最后一道防线，
 * 不是面向 API 的字段错误来源。这样越界请求能指出具体字段，同时失败时旧配置不会被部分覆盖。
 */
@Service
public class AppSettingService {

    private static final short SETTINGS_ROW_ID = 1;

    private static final int SPAM_JUDGMENT_PROMPT_MAX_LENGTH = 20_000;

    private static final Set<String> GUARDRAIL_FIELDS = Set.of(
            "initialSyncDays", "threadSizeLimit", "processingRetryLimit",
            "searchResultLimit", "turnModelCallLimit", "turnTimeoutSeconds",
            "pendingActionTtlHours", "toolTimeoutSeconds", "smtpTimeoutSeconds",
            "autoSyncEnabled", "imapSyncIntervalSeconds",
            "autoDeleteEnabled", "messageRetentionDays");

    private static final Set<String> PIPELINE_FIELDS = Set.of(
            "spamCheckEnabled", "spamClassificationThreshold", "spamJudgmentPrompt",
            "classifyEnabled", "taggingEnabled", "languageTranslationEnabled",
            "summaryEnabled", "threadSummaryEnabled");

    private final AppSettingMapper appSettingMapper;

    public AppSettingService(AppSettingMapper appSettingMapper) {
        this.appSettingMapper = appSettingMapper;
    }

    @Transactional(readOnly = true)
    public GuardrailSettings getGuardrails() {
        return GuardrailSettings.from(getSetting());
    }

    @Transactional
    public GuardrailSettings updateGuardrails(GuardrailPatch patch) {
        List<ValidationErrorItem> errors = new ArrayList<>();
        patch.validate(errors);
        if (!errors.isEmpty()) {
            throw guardrailValidationFailed(errors);
        }

        LambdaUpdateWrapper<AppSetting> update = settingUpdate();
        patch.applyTo(update);
        persist(update, ApiError.GUARDRAIL_OUT_OF_RANGE);
        return GuardrailSettings.from(getSetting());
    }

    @Transactional(readOnly = true)
    public PipelineSettings getPipelineSettings() {
        return PipelineSettings.from(getSetting());
    }

    @Transactional
    public PipelineSettings updatePipelineSettings(PipelinePatch patch) {
        List<ValidationErrorItem> errors = new ArrayList<>();
        patch.validate(errors);
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }

        LambdaUpdateWrapper<AppSetting> update = settingUpdate();
        patch.applyTo(update);
        persist(update, ApiError.VALIDATION_FAILED);
        return PipelineSettings.from(getSetting());
    }

    private AppSetting getSetting() {
        AppSetting setting = appSettingMapper.selectById(SETTINGS_ROW_ID);
        if (setting == null) {
            // migration 固定插入 id=1；缺行意味着部署不完整，继续执行会把问题伪装成空配置。
            throw new ApiException(ApiError.INTERNAL_ERROR, "应用配置行不存在");
        }
        return setting;
    }

    private LambdaUpdateWrapper<AppSetting> settingUpdate() {
        return new LambdaUpdateWrapper<AppSetting>()
                .eq(AppSetting::getId, SETTINGS_ROW_ID);
    }

    private void persist(LambdaUpdateWrapper<AppSetting> update, ApiError constraintError) {
        try {
            // 只更新 PATCH 中出现的列。若先读整行再 updateById，并发修改另一组设置时，
            // 较晚提交的请求会用旧快照把对方的修改静默覆盖。
            int affectedRows = appSettingMapper.update(new AppSetting(), update);
            if (affectedRows != 1) {
                throw new ApiException(ApiError.INTERNAL_ERROR, "应用配置更新未生效");
            }
        } catch (DataIntegrityViolationException ex) {
            // 正常请求在应用层已被拦住；这里仅兜住并发/绕过应用层写入等异常，避免把数据库细节回显给客户端。
            if (constraintError == ApiError.GUARDRAIL_OUT_OF_RANGE) {
                throw new ApiException(ApiError.GUARDRAIL_OUT_OF_RANGE);
            }
            if (constraintError == ApiError.VALIDATION_FAILED) {
                throw ApiException.validationFailed(List.of(
                        new ValidationErrorItem("$", "流水线设置未通过数据库约束")));
            }
            throw new ApiException(ApiError.INTERNAL_ERROR);
        }
    }

    /** 护栏 patch 的可读字段集合与范围校验。null 表示未传，不会清空当前值。 */
    public record GuardrailPatch(
            Integer initialSyncDays,
            Integer threadSizeLimit,
            Integer processingRetryLimit,
            Integer searchResultLimit,
            Integer turnModelCallLimit,
            Integer turnTimeoutSeconds,
            Integer pendingActionTtlHours,
            Integer toolTimeoutSeconds,
            Integer smtpTimeoutSeconds,
            Boolean autoSyncEnabled,
            Integer imapSyncIntervalSeconds,
            Boolean autoDeleteEnabled,
            Integer messageRetentionDays,
            Set<String> providedFields) {

        public GuardrailPatch {
            providedFields = Set.copyOf(providedFields);
            if (!GUARDRAIL_FIELDS.containsAll(providedFields)) {
                throw new IllegalArgumentException("护栏 patch 包含未知字段");
            }
        }

        public void validate(List<ValidationErrorItem> errors) {
            validateProvidedRange(errors, providedFields, "initialSyncDays", initialSyncDays, 1, 365);
            validateProvidedRange(errors, providedFields, "threadSizeLimit", threadSizeLimit, 10, 10_000);
            validateProvidedRange(errors, providedFields, "processingRetryLimit", processingRetryLimit, 0, 20);
            validateProvidedRange(errors, providedFields, "searchResultLimit", searchResultLimit, 1, 200);
            validateProvidedRange(errors, providedFields, "turnModelCallLimit", turnModelCallLimit, 1, 50);
            validateProvidedRange(errors, providedFields, "turnTimeoutSeconds", turnTimeoutSeconds, 10, 1_800);
            validateProvidedRange(errors, providedFields, "pendingActionTtlHours", pendingActionTtlHours, 1, 720);
            validateProvidedRange(errors, providedFields, "toolTimeoutSeconds", toolTimeoutSeconds, 5, 300);
            validateProvidedRange(errors, providedFields, "smtpTimeoutSeconds", smtpTimeoutSeconds, 5, 300);
            validateProvidedRange(errors, providedFields, "imapSyncIntervalSeconds", imapSyncIntervalSeconds, 30, 3_600);
            validateProvidedRange(errors, providedFields, "messageRetentionDays", messageRetentionDays, 1, 3_650);
            validateSwitch(errors, providedFields, "autoSyncEnabled", autoSyncEnabled);
            validateSwitch(errors, providedFields, "autoDeleteEnabled", autoDeleteEnabled);
            if (providedFields.isEmpty()) {
                errors.add(new ValidationErrorItem("$", "至少需要提供一个护栏参数"));
            }
        }

        public void applyTo(LambdaUpdateWrapper<AppSetting> update) {
            if (providedFields.contains("initialSyncDays")) {
                update.set(AppSetting::getInitialSyncDays, initialSyncDays);
            }
            if (providedFields.contains("threadSizeLimit")) {
                update.set(AppSetting::getThreadSizeLimit, threadSizeLimit);
            }
            if (providedFields.contains("processingRetryLimit")) {
                update.set(AppSetting::getProcessingRetryLimit, processingRetryLimit);
            }
            if (providedFields.contains("searchResultLimit")) {
                update.set(AppSetting::getSearchResultLimit, searchResultLimit);
            }
            if (providedFields.contains("turnModelCallLimit")) {
                update.set(AppSetting::getTurnModelCallLimit, turnModelCallLimit);
            }
            if (providedFields.contains("turnTimeoutSeconds")) {
                update.set(AppSetting::getTurnTimeoutSeconds, turnTimeoutSeconds);
            }
            if (providedFields.contains("pendingActionTtlHours")) {
                update.set(AppSetting::getPendingActionTtlHours, pendingActionTtlHours);
            }
            if (providedFields.contains("toolTimeoutSeconds")) {
                update.set(AppSetting::getToolTimeoutSeconds, toolTimeoutSeconds);
            }
            if (providedFields.contains("smtpTimeoutSeconds")) {
                update.set(AppSetting::getSmtpTimeoutSeconds, smtpTimeoutSeconds);
            }
            if (providedFields.contains("autoSyncEnabled")) {
                update.set(AppSetting::getAutoSyncEnabled, autoSyncEnabled);
            }
            if (providedFields.contains("imapSyncIntervalSeconds")) {
                update.set(AppSetting::getImapSyncIntervalSeconds, imapSyncIntervalSeconds);
            }
            if (providedFields.contains("autoDeleteEnabled")) {
                update.set(AppSetting::getAutoDeleteEnabled, autoDeleteEnabled);
            }
            if (providedFields.contains("messageRetentionDays")) {
                update.set(AppSetting::getMessageRetentionDays, messageRetentionDays);
            }
        }
    }

    /**
     * 流水线 patch。包装类型和字段集合共同保留“未传”“显式 false/0”“显式 null”的区别。
     *
     * <p>record 默认 {@code toString()} 会展开用户完整评分政策，因此显式覆盖，只记录字段名。
     */
    public record PipelinePatch(
            Boolean spamCheckEnabled,
            BigDecimal spamClassificationThreshold,
            String spamJudgmentPrompt,
            Boolean classifyEnabled,
            Boolean taggingEnabled,
            Boolean languageTranslationEnabled,
            Boolean summaryEnabled,
            Boolean threadSummaryEnabled,
            Set<String> providedFields) {

        public PipelinePatch {
            providedFields = Set.copyOf(providedFields);
            if (!PIPELINE_FIELDS.containsAll(providedFields)) {
                throw new IllegalArgumentException("流水线设置 patch 包含未知字段");
            }
        }

        public void validate(List<ValidationErrorItem> errors) {
            if (providedFields.isEmpty()) {
                errors.add(new ValidationErrorItem("$", "至少需要提供一个流水线设置"));
            }
            validateSwitch(errors, providedFields, "spamCheckEnabled", spamCheckEnabled);
            validateSwitch(errors, providedFields, "classifyEnabled", classifyEnabled);
            validateSwitch(errors, providedFields, "taggingEnabled", taggingEnabled);
            validateSwitch(errors, providedFields, "languageTranslationEnabled", languageTranslationEnabled);
            validateSwitch(errors, providedFields, "summaryEnabled", summaryEnabled);
            validateSwitch(errors, providedFields, "threadSummaryEnabled", threadSummaryEnabled);
            validateSpamClassificationThreshold(errors);
            validateSpamJudgmentPrompt(errors);
        }

        public void applyTo(LambdaUpdateWrapper<AppSetting> update) {
            if (providedFields.contains("spamCheckEnabled")) {
                update.set(AppSetting::getAiSpamCheckEnabled, spamCheckEnabled);
            }
            if (providedFields.contains("spamClassificationThreshold")) {
                update.set(AppSetting::getSpamClassificationThreshold, spamClassificationThreshold);
            }
            if (providedFields.contains("spamJudgmentPrompt")) {
                update.set(AppSetting::getSpamJudgmentPrompt, spamJudgmentPrompt);
            }
            if (providedFields.contains("classifyEnabled")) {
                update.set(AppSetting::getAiClassifyEnabled, classifyEnabled);
            }
            if (providedFields.contains("taggingEnabled")) {
                update.set(AppSetting::getAiTaggingEnabled, taggingEnabled);
            }
            if (providedFields.contains("languageTranslationEnabled")) {
                update.set(AppSetting::getAiLanguageTranslationEnabled, languageTranslationEnabled);
            }
            if (providedFields.contains("summaryEnabled")) {
                update.set(AppSetting::getAiSummaryEnabled, summaryEnabled);
            }
            if (providedFields.contains("threadSummaryEnabled")) {
                update.set(AppSetting::getAiThreadSummaryEnabled, threadSummaryEnabled);
            }
        }

        @Override
        public String toString() {
            return "PipelinePatch[providedFields=" + providedFields + "]";
        }

        private void validateSpamClassificationThreshold(List<ValidationErrorItem> errors) {
            if (!providedFields.contains("spamClassificationThreshold")) {
                return;
            }
            if (spamClassificationThreshold == null) {
                errors.add(new ValidationErrorItem("spamClassificationThreshold", "值不能为 null"));
                return;
            }
            if (spamClassificationThreshold.compareTo(BigDecimal.ZERO) < 0
                    || spamClassificationThreshold.compareTo(BigDecimal.ONE) > 0) {
                errors.add(new ValidationErrorItem(
                        "spamClassificationThreshold", "必须在 0 到 1 之间"));
            } else if (Math.max(0, spamClassificationThreshold.stripTrailingZeros().scale()) > 3) {
                // 按数值精度而不是词法尾零判断：0.8000 与 0.8 是同一个合法阈值，
                // 但 0.8001 会被 numeric(4,3) 静默舍入，必须在入口拒绝。
                errors.add(new ValidationErrorItem(
                        "spamClassificationThreshold", "最多允许三位有效小数"));
            }
        }

        private void validateSpamJudgmentPrompt(List<ValidationErrorItem> errors) {
            if (!providedFields.contains("spamJudgmentPrompt")) {
                return;
            }
            if (spamJudgmentPrompt == null) {
                errors.add(new ValidationErrorItem("spamJudgmentPrompt", "值不能为 null"));
            } else if (spamJudgmentPrompt.isBlank()) {
                errors.add(new ValidationErrorItem("spamJudgmentPrompt", "不能为空白"));
            } else {
                String trimmedPrompt = spamJudgmentPrompt.strip();
                if (trimmedPrompt.codePointCount(0, trimmedPrompt.length())
                        <= SPAM_JUDGMENT_PROMPT_MAX_LENGTH) {
                    return;
                }
                // 契约按 trim 后长度校验；仍保留用户原文。用 code point 计数，避免一个 emoji
                // 被 Java UTF-16 length 误算成两个字符，并与 OpenAPI/PostgreSQL 字符语义对齐。
                errors.add(new ValidationErrorItem(
                        "spamJudgmentPrompt", "长度不能超过 " + SPAM_JUDGMENT_PROMPT_MAX_LENGTH + " 个字符"));
            }
        }
    }

    private static ApiException guardrailValidationFailed(List<ValidationErrorItem> errors) {
        boolean containsOnlyRangeErrors = errors.stream()
                .noneMatch(error -> "$".equals(error.field()) || error.message().contains("null"));
        if (!containsOnlyRangeErrors) {
            return ApiException.validationFailed(errors);
        }

        // OpenAPI 明确只有 VALIDATION_FAILED 带 errors 扩展成员。422 仍在 detail 中逐项写出字段名，
        // 既满足定位输入框的需要，又不让响应形状偏离 ProblemDetail 契约。
        String detail = errors.stream()
                .map(error -> error.field() + " " + error.message())
                .reduce((left, right) -> left + "；" + right)
                .orElse(null);
        return new ApiException(ApiError.GUARDRAIL_OUT_OF_RANGE, detail);
    }

    private static void validateProvidedRange(List<ValidationErrorItem> errors, Set<String> providedFields,
                                              String field, Integer value, int minimum, int maximum) {
        if (!providedFields.contains(field)) {
            return;
        }
        if (value == null) {
            errors.add(new ValidationErrorItem(field, "值不能为 null"));
        } else if (value < minimum || value > maximum) {
            errors.add(new ValidationErrorItem(
                    field, "必须在 " + minimum + " 到 " + maximum + " 之间"));
        }
    }

    private static void validateSwitch(List<ValidationErrorItem> errors, Set<String> providedFields,
                                       String field, Boolean value) {
        if (providedFields.contains(field) && value == null) {
            errors.add(new ValidationErrorItem(field, "值不能为 null"));
        }
    }

}
