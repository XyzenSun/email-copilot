package com.xyzensun.emailcopilot.domain.enums;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * 判定流水线的阶段游标，指的是<b>下一项待执行阶段</b>（{@code DATABASE.md} §4.4）。
 *
 * <p>顺序不可调换（{@code ARCHITECTURE.md} §2.1）：
 * <pre>
 * SENDER_RULE → SPAM_JUDGMENT → CLASSIFICATION → LANGUAGE_DETECTION
 *             → TRANSLATION → SUMMARY → DONE
 * </pre>
 *
 * <p>{@code SPAM_JUDGMENT} 是一次无工具 structured-output 垃圾评分调用，
 * 不访问邮件 URL，也不执行外部网页取证或 tool loop；自动分类与自动标签在
 * {@code CLASSIFICATION} 内部阶段共用至多一次模型调用，但由两个独立开关分别控制结果。
 *
 * <p>{@code LANGUAGE_DETECTION} 与 {@code TRANSLATION} 共用一个用户开关，
 * 内部仍分成两个阶段以支持翻译失败后的断点恢复。
 *
 * <p><b>五个流水线开关在阶段游标推进时检查，关闭功能即跳过并把游标推到下一个</b>——
 * 不是只在调用点判断（{@code ARCHITECTURE.md} §8.7）。在调用点判断会留下一批
 * 游标停在被关掉的阶段上、永远处理不完的邮件。自动分类和自动标签都关闭时，
 * {@code CLASSIFICATION} 整体跳过；只关闭其中一个时只写入另一个启用结果。
 */
public enum ProcessingStage implements IEnum<String> {

    SENDER_RULE("sender_rule"),
    SPAM_JUDGMENT("spam_judgment"),
    CLASSIFICATION("classification"),
    LANGUAGE_DETECTION("language_detection"),
    TRANSLATION("translation"),
    SUMMARY("summary"),
    DONE("done");

    private final String value;

    ProcessingStage(String value) {
        this.value = value;
    }

    @Override
    public String getValue() {
        return value;
    }
}
