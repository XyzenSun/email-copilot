package com.xyzensun.emailcopilot.interfaces.settings.dto;

import jakarta.validation.constraints.NotEmpty;

/** 外部凭据请求；默认 record toString 会泄漏原文，因此必须覆盖。 */
public record MailAccountSecretValueRequest(@NotEmpty String value) {

    @Override
    public String toString() {
        return "MailAccountSecretValueRequest[value=<已隐藏>]";
    }
}
