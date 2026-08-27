package com.xyzensun.emailcopilot.domain.pipeline;

import com.xyzensun.emailcopilot.application.settings.PipelineSettings;
import com.xyzensun.emailcopilot.domain.enums.MessageCategory;
import com.xyzensun.emailcopilot.domain.enums.ProcessingStage;

import java.math.BigDecimal;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * 判定流水线的纯阶段转换表。
 *
 * <p>把跳过逻辑集中在一个位置，避免 worker 在调用点零散判断后把游标留在已关闭阶段。
 */
public final class ProcessingStageTransition {

    public ProcessingStage afterSenderRule(SenderRuleOutcome outcome) {
        return switch (requireNonNull(outcome, "发件人规则结果不能为空")) {
            case BLOCK -> ProcessingStage.DONE;
            case TRUST -> ProcessingStage.CLASSIFICATION;
            case MISS -> ProcessingStage.SPAM_JUDGMENT;
        };
    }

    public ProcessingStage afterSpamScore(BigDecimal spamScore, BigDecimal threshold) {
        return isSpam(spamScore, threshold)
                ? ProcessingStage.DONE
                : ProcessingStage.CLASSIFICATION;
    }

    public Optional<MessageCategory> categoryForSpamScore(BigDecimal spamScore, BigDecimal threshold) {
        return isSpam(spamScore, threshold)
                ? Optional.of(MessageCategory.SPAM)
                : Optional.empty();
    }

    public ProcessingStage afterLanguageDetection(LanguageDetection detection) {
        return requireNonNull(detection, "语言判断结果不能为空") == LanguageDetection.NON_CHINESE
                ? ProcessingStage.TRANSLATION
                : ProcessingStage.SUMMARY;
    }

    public ClassificationMode classificationMode(PipelineSettings settings) {
        requireNonNull(settings, "流水线设置不能为空");
        if (settings.classifyEnabled() && settings.taggingEnabled()) {
            return ClassificationMode.CATEGORY_AND_TAGS;
        }
        if (settings.classifyEnabled()) {
            return ClassificationMode.CATEGORY_ONLY;
        }
        if (settings.taggingEnabled()) {
            return ClassificationMode.TAGS_ONLY;
        }
        return ClassificationMode.SKIP;
    }

    /** 返回从当前游标开始第一个需要实际执行的阶段，关闭功能会连续跳过。 */
    public ProcessingStage firstExecutableStage(ProcessingStage currentStage, PipelineSettings settings) {
        requireNonNull(currentStage, "当前阶段不能为空");
        requireNonNull(settings, "流水线设置不能为空");

        ProcessingStage stage = currentStage;
        while (true) {
            stage = switch (stage) {
                case SENDER_RULE, DONE -> stage;
                case SPAM_JUDGMENT -> settings.spamCheckEnabled()
                        ? stage : ProcessingStage.CLASSIFICATION;
                case CLASSIFICATION -> classificationMode(settings) == ClassificationMode.SKIP
                        ? ProcessingStage.LANGUAGE_DETECTION : stage;
                case LANGUAGE_DETECTION, TRANSLATION -> settings.languageTranslationEnabled()
                        ? stage : ProcessingStage.SUMMARY;
                case SUMMARY -> settings.summaryEnabled() ? stage : ProcessingStage.DONE;
            };

            if (stage == ProcessingStage.SPAM_JUDGMENT && settings.spamCheckEnabled()
                    || stage == ProcessingStage.CLASSIFICATION
                    && classificationMode(settings) != ClassificationMode.SKIP
                    || (stage == ProcessingStage.LANGUAGE_DETECTION || stage == ProcessingStage.TRANSLATION)
                    && settings.languageTranslationEnabled()
                    || stage == ProcessingStage.SUMMARY && settings.summaryEnabled()
                    || stage == ProcessingStage.SENDER_RULE
                    || stage == ProcessingStage.DONE) {
                return stage;
            }
        }
    }

    private static boolean isSpam(BigDecimal spamScore, BigDecimal threshold) {
        requireNonNull(spamScore, "垃圾分数不能为空");
        requireNonNull(threshold, "垃圾阈值不能为空");
        return spamScore.compareTo(threshold) >= 0;
    }
}
