package com.xyzensun.emailcopilot.interfaces.settings.dto;

import com.xyzensun.emailcopilot.infrastructure.settings.MaintenanceTaskSnapshot;

import java.time.OffsetDateTime;

/** 内部枚举显式转契约字面值，避免 Jackson 默认输出大写常量名。 */
public record MaintenanceTaskResponse(
        String id,
        String type,
        String status,
        String progress,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String error) {

    public static MaintenanceTaskResponse from(MaintenanceTaskSnapshot snapshot) {
        return new MaintenanceTaskResponse(
                snapshot.id(),
                snapshot.type().getValue(),
                snapshot.status().getValue(),
                snapshot.progress(),
                snapshot.startedAt(),
                snapshot.finishedAt(),
                snapshot.error());
    }
}
