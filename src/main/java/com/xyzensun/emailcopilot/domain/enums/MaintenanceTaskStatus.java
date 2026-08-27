package com.xyzensun.emailcopilot.domain.enums;

/** 内存维护任务状态，对应 {@code openapi.yaml} 的 {@code TaskStatus}。 */
public enum MaintenanceTaskStatus {

    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed");

    private final String value;

    MaintenanceTaskStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
