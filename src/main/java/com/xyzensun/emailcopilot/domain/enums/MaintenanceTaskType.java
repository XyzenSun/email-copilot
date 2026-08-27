package com.xyzensun.emailcopilot.domain.enums;

/** 内存维护任务类型，对应 {@code openapi.yaml} 的 {@code TaskType}。阶段11 起 purge 作废。 */
public enum MaintenanceTaskType {

    SYNC("sync"),
    ACCOUNT_DELETE("account-delete");

    private final String value;

    MaintenanceTaskType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
