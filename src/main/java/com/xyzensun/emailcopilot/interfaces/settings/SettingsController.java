package com.xyzensun.emailcopilot.interfaces.settings;

import com.xyzensun.emailcopilot.application.settings.AppSettingService;
import com.xyzensun.emailcopilot.interfaces.settings.dto.GuardrailsResponse;
import com.xyzensun.emailcopilot.interfaces.settings.dto.GuardrailsUpdateRequest;
import com.xyzensun.emailcopilot.interfaces.settings.dto.PipelineSettingsResponse;
import com.xyzensun.emailcopilot.interfaces.settings.dto.PipelineSettingsUpdateRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 护栏参数与 AI 阶段开关的 HTTP 入口（{@code API.md} §12.6、§12.8）。
 *
 * <p>Controller 只负责 DTO 与应用快照之间的转换；读取、校验、事务更新都集中在
 * {@link AppSettingService}，避免另一个接口绕过单行配置的统一规则。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final AppSettingService appSettingService;

    public SettingsController(AppSettingService appSettingService) {
        this.appSettingService = appSettingService;
    }

    @GetMapping("/guardrails")
    public GuardrailsResponse getGuardrails() {
        return GuardrailsResponse.from(appSettingService.getGuardrails());
    }

    @PatchMapping("/guardrails")
    public GuardrailsResponse updateGuardrails(@RequestBody GuardrailsUpdateRequest request) {
        return GuardrailsResponse.from(appSettingService.updateGuardrails(request.toPatch()));
    }

    @GetMapping("/pipeline")
    public PipelineSettingsResponse getPipelineSettings() {
        return PipelineSettingsResponse.from(appSettingService.getPipelineSettings());
    }

    @PatchMapping("/pipeline")
    public PipelineSettingsResponse updatePipelineSettings(
            @RequestBody PipelineSettingsUpdateRequest request) {
        return PipelineSettingsResponse.from(
                appSettingService.updatePipelineSettings(request.toPatch()));
    }
}
