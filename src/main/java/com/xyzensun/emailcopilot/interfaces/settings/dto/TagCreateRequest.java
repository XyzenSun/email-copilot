package com.xyzensun.emailcopilot.interfaces.settings.dto;

import jakarta.validation.constraints.NotBlank;

/** 新建标签请求，对应 {@code openapi.yaml TagCreateRequest}。 */
public record TagCreateRequest(
        @NotBlank(message = "标签标识不能为空") String name,
        @NotBlank(message = "展示名不能为空") String displayName,
        @NotBlank(message = "标签判定依据不能为空") String description) {
}
