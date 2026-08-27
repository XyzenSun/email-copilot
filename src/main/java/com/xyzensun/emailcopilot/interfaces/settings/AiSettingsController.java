package com.xyzensun.emailcopilot.interfaces.settings;

import com.xyzensun.emailcopilot.application.settings.AiSettingsService;
import com.xyzensun.emailcopilot.interfaces.settings.dto.AiApiKeyRequest;
import com.xyzensun.emailcopilot.interfaces.settings.dto.AiTestResultResponse;
import com.xyzensun.emailcopilot.interfaces.settings.dto.SystemSettingsResponse;
import com.xyzensun.emailcopilot.interfaces.settings.dto.SystemSettingsUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** AI system settings 的 HTTP 入口；事务、热替换与错误映射均留在独立应用服务中。 */
@RestController
@RequestMapping("/api/settings/system")
public class AiSettingsController {

    private final AiSettingsService aiSettingsService;

    public AiSettingsController(AiSettingsService aiSettingsService) {
        this.aiSettingsService = aiSettingsService;
    }

    @GetMapping
    public SystemSettingsResponse getSystemSettings() {
        return SystemSettingsResponse.from(aiSettingsService.getSystemSettings());
    }

    @PatchMapping
    public SystemSettingsResponse updateSystemSettings(
            @RequestBody SystemSettingsUpdateRequest request) {
        return SystemSettingsResponse.from(aiSettingsService.updateSystemSettings(request.toPatch()));
    }

    @PutMapping("/ai-key")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void putAiApiKey(@Valid @RequestBody AiApiKeyRequest request) {
        aiSettingsService.saveAiApiKey(request.value());
    }

    /**
     * 写入或覆盖 Exa MCP API key（design.md §8.2，仿 ai-key）。
     * 204 永不回显：不回明文/掩码/长度，不进日志。
     */
    @PutMapping("/mcp-key")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void putMcpApiKey(@Valid @RequestBody AiApiKeyRequest request) {
        aiSettingsService.saveMcpApiKey(request.value());
    }

    /**
     * 写入或覆盖 Tavily API key（阶段10 prd A1，仿 mcp-key）。
     * 204 永不回显：不回明文/掩码/长度，不进日志。本阶段只存 key 暂不接入对话工具链。
     */
    @PutMapping("/tavily-key")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void putTavilyApiKey(@Valid @RequestBody AiApiKeyRequest request) {
        aiSettingsService.saveTavilyApiKey(request.value());
    }

    @PostMapping("/test")
    public AiTestResultResponse testAiConnection() {
        return AiTestResultResponse.from(aiSettingsService.testAiConnection());
    }
}
