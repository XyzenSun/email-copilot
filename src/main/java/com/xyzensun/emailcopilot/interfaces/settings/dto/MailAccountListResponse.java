package com.xyzensun.emailcopilot.interfaces.settings.dto;

import java.util.List;

/** 配置资源数量很少，契约明确不分页。 */
public record MailAccountListResponse(List<MailAccountResponse> items) {
}
