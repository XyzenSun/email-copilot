package com.xyzensun.emailcopilot.interfaces.settings.dto;

import com.xyzensun.emailcopilot.application.settings.model.MailConnectionTestResult;

/** 连接失败仍返回 HTTP 200，由 ok 分支表达。 */
public record MailAccountConnectionTestResponse(boolean ok, String message) {

    public static MailAccountConnectionTestResponse from(MailConnectionTestResult result) {
        return new MailAccountConnectionTestResponse(result.ok(), result.message());
    }
}
