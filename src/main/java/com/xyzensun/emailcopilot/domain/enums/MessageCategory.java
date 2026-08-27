package com.xyzensun.emailcopilot.domain.enums;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * 邮件分类，判定流水线的 {@code classification} 阶段写入（{@code DATABASE.md} §3.2）。
 *
 * <p>{@code SPAM} 既是一个分类值，也是检索与列表的默认排除条件。
 *
 * <p>关掉 {@code ai_classify_enabled} 后 {@code category} 保持 null，
 * 列表的分类筛选选什么都是空（{@code DATABASE.md} §8.5.2）。
 * {@code direction='outbound'} 的邮件此列恒为 null（check 约束保证）。
 */
public enum MessageCategory implements IEnum<String> {

    PRIMARY("primary"),
    TRANSACTION("transaction"),
    PROMOTION("promotion"),
    SOCIAL("social"),
    UPDATE("update"),
    SPAM("spam");

    private final String value;

    MessageCategory(String value) {
        this.value = value;
    }

    @Override
    public String getValue() {
        return value;
    }
}
