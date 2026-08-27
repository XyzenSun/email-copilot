package com.xyzensun.emailcopilot.domain.enums;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * 单封邮件当前阶段的执行状态（{@code DATABASE.md} §4.4）。
 *
 * <p>{@code IN_PROGRESS} 时 {@code in_progress_since} 必填（check 约束保证），用于识别僵尸；
 * {@code FAILED} 时 {@code last_error_code} 必填。
 *
 * <p>重试耗尽即 {@code FAILED}，用户在失败列表里自行决定是否重试——
 * <b>不设 {@code awaiting_review} 状态</b>，本项目不提供人工复核环节。
 *
 * <p>AI 服务不可用只让状态进入可重试的 {@code FAILED}，<b>绝不回滚邮件入库</b>
 * （{@code ARCHITECTURE.md} §2.1）。
 */
public enum ProcessingStatus implements IEnum<String> {

    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    FAILED("failed");

    private final String value;

    ProcessingStatus(String value) {
        this.value = value;
    }

    @Override
    public String getValue() {
        return value;
    }
}
