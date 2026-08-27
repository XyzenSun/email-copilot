package com.xyzensun.emailcopilot.interfaces.settings;

import com.xyzensun.emailcopilot.application.settings.MaintenanceApplicationService;
import com.xyzensun.emailcopilot.interfaces.settings.dto.MaintenanceTaskResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内存维护任务查询入口。
 *
 * <p>阶段11 起 DataPurge 已作废（删除时同步清正文，无 purge 目标）——purge-preview / purge
 * 端点移除。仅保留 {@code tasks/{taskId}}：account-delete / sync 任务进度仍用。
 */
@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {

    private final MaintenanceApplicationService maintenanceApplicationService;

    public MaintenanceController(MaintenanceApplicationService maintenanceApplicationService) {
        this.maintenanceApplicationService = maintenanceApplicationService;
    }

    @GetMapping("/tasks/{taskId}")
    public MaintenanceTaskResponse getTask(@PathVariable String taskId) {
        return MaintenanceTaskResponse.from(maintenanceApplicationService.getTask(taskId));
    }
}
