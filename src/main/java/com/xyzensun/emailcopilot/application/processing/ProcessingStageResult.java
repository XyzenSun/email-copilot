package com.xyzensun.emailcopilot.application.processing;

import com.xyzensun.emailcopilot.domain.enums.MessageCategory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 一个阶段允许写回 {@code message} 的 typed 结果。
 *
 * <p>显式 write 标记区分“本阶段不处理此字段”和“要写空值”，避免关闭分类时误清用户已有数据。
 */
public record ProcessingStageResult(
        boolean writeSpamScore,
        BigDecimal spamScore,
        boolean writeCategory,
        MessageCategory category,
        boolean writeTags,
        List<Long> tagIds,
        boolean writeTranslation,
        String translatedBody,
        boolean writeSummary,
        String summary,
        boolean markClassified) {

    public ProcessingStageResult {
        tagIds = tagIds == null ? List.of() : List.copyOf(tagIds);
        if (writeSpamScore && spamScore == null) {
            throw new IllegalArgumentException("写入垃圾分数时 spamScore 不能为空");
        }
        if (writeCategory && category == null) {
            throw new IllegalArgumentException("写入分类时 category 不能为空");
        }
        if (writeTranslation && translatedBody == null) {
            throw new IllegalArgumentException("写入译文时 translatedBody 不能为空");
        }
        if (writeSummary && summary == null) {
            throw new IllegalArgumentException("写入摘要时 summary 不能为空");
        }
    }

    public static ProcessingStageResult none() {
        return new ProcessingStageResult(
                false, null, false, null, false, List.of(),
                false, null, false, null, false);
    }

    public static ProcessingStageResult blockedAsSpam() {
        return new ProcessingStageResult(
                false, null, true, MessageCategory.SPAM, false, List.of(),
                false, null, false, null, true);
    }

    public static ProcessingStageResult spamScore(BigDecimal score, boolean spam) {
        return new ProcessingStageResult(
                true, score, spam, spam ? MessageCategory.SPAM : null, false, List.of(),
                false, null, false, null, spam);
    }

    public static ProcessingStageResult classification(
            Optional<MessageCategory> category,
            List<Long> tagIds,
            boolean writeTags) {
        Optional<MessageCategory> safeCategory = category == null ? Optional.empty() : category;
        return new ProcessingStageResult(
                false, null,
                safeCategory.isPresent(), safeCategory.orElse(null),
                writeTags, tagIds,
                false, null, false, null, true);
    }

    public static ProcessingStageResult translation(String translatedBody) {
        return new ProcessingStageResult(
                false, null, false, null, false, List.of(),
                true, translatedBody, false, null, false);
    }

    public static ProcessingStageResult summary(String summary) {
        return new ProcessingStageResult(
                false, null, false, null, false, List.of(),
                false, null, true, summary, false);
    }

    public boolean writesMessage() {
        return writeSpamScore || writeCategory || writeTags || writeTranslation || writeSummary
                || markClassified;
    }

    @Override
    public String toString() {
        return "ProcessingStageResult[writeSpamScore=" + writeSpamScore
                + ", spamScore=" + spamScore
                + ", writeCategory=" + writeCategory
                + ", category=" + category
                + ", writeTags=" + writeTags
                + ", tagIds=" + tagIds
                + ", writeTranslation=" + writeTranslation
                + ", translatedBody=<已隐藏>"
                + ", writeSummary=" + writeSummary
                + ", summary=<已隐藏>"
                + ", markClassified=" + markClassified + "]";
    }
}
