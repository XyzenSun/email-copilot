package com.xyzensun.emailcopilot.interfaces.settings.dto;

import jakarta.validation.constraints.NotBlank;

/** AI API key 写入请求；覆盖 record 默认实现，避免日志展开明文。 */
public record AiApiKeyRequest(
        @NotBlank(message = "AI API key 不能为空") String value) {

    @Override
    public String toString() {
        return "AiApiKeyRequest[value=<已隐藏>]";
    }
}
