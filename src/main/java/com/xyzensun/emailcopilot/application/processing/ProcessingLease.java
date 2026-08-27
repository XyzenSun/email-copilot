package com.xyzensun.emailcopilot.application.processing;

import java.time.OffsetDateTime;

/** 一次消息处理领取的 fencing 快照。 */
public record ProcessingLease(
        long messageId,
        String workerId,
        int version,
        OffsetDateTime claimUntil) {

    public ProcessingLease {
        if (messageId <= 0) {
            throw new IllegalArgumentException("messageId 必须为正数");
        }
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId 不能为空");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("fencing version 必须为正数");
        }
        if (claimUntil == null) {
            throw new IllegalArgumentException("claimUntil 不能为空");
        }
    }
}
