package com.xyzensun.emailcopilot.interfaces.settings.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 连接探测只接受 imap 或 smtp 通道。 */
public record MailAccountConnectionTestRequest(
        @NotNull @Pattern(regexp = "imap|smtp", message = "只支持 imap 或 smtp") String channel) {
}
