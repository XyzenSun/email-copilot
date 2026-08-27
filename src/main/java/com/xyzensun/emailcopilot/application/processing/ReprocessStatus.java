package com.xyzensun.emailcopilot.application.processing;

/**
 * 手动重新处理的执行结果状态。{@code design.md} §3.2：与 approve 同步语义——
 * 动作被尝试了但 AI 可能返回不合规输出，记 {@code failed} 而非 5xx。
 */
public enum ReprocessStatus {
    SUCCEEDED("succeeded"),
    FAILED("failed");

    private final String value;

    ReprocessStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
