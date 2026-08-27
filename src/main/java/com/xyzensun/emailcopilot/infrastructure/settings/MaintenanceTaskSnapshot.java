package com.xyzensun.emailcopilot.infrastructure.settings;

import com.xyzensun.emailcopilot.domain.enums.MaintenanceTaskStatus;
import com.xyzensun.emailcopilot.domain.enums.MaintenanceTaskType;

import java.time.OffsetDateTime;

/** 对外只暴露不可变快照，避免 Controller 观察到一半更新的任务状态。 */
public record MaintenanceTaskSnapshot(
        String id,
        MaintenanceTaskType type,
        MaintenanceTaskStatus status,
        String progress,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String error) {
}
