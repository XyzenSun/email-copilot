package com.xyzensun.emailcopilot.domain.enums;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * 读取证据的目标类型（{@code DATABASE.md} §5.3）。
 *
 * <p>{@code MESSAGE} 时 {@code target_id} 是 {@code message.id}，
 * {@code THREAD} 时是 {@code thread_node.id}。
 */
public enum EvidenceTargetType implements IEnum<String> {

    MESSAGE("message"),
    THREAD("thread");

    private final String value;

    EvidenceTargetType(String value) {
        this.value = value;
    }

    @Override
    public String getValue() {
        return value;
    }
}
