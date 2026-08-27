package com.xyzensun.emailcopilot.interfaces.mail.dto;

import java.util.List;

/** 批量删除请求（openapi BatchDeleteRequest）。 */
public record BatchDeleteRequest(List<Long> ids) {
}
