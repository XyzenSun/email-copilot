package com.xyzensun.emailcopilot.application.settings;

import com.xyzensun.emailcopilot.infrastructure.settings.MaintenanceTaskRegistry;
import com.xyzensun.emailcopilot.infrastructure.settings.MaintenanceTaskSnapshot;
import com.xyzensun.emailcopilot.interfaces.error.ApiError;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import org.springframework.stereotype.Service;

/**
 * 内存维护任务查询的用例编排。
 *
 * <p>阶段11 起 DataPurge 作废（删除时同步清正文，库内无 purge 目标）——purge 预览/触发
 * 方法已移除。仅保留 {@code getTask}：account-delete / sync 任务进度查询。
 */
@Service
public class MaintenanceApplicationService {

    private final MaintenanceTaskRegistry maintenanceTaskRegistry;

    public MaintenanceApplicationService(MaintenanceTaskRegistry maintenanceTaskRegistry) {
        this.maintenanceTaskRegistry = maintenanceTaskRegistry;
    }

    public MaintenanceTaskSnapshot getTask(String taskId) {
        return maintenanceTaskRegistry.get(taskId)
                .orElseThrow(() -> new ApiException(ApiError.TASK_NOT_FOUND));
    }
}
