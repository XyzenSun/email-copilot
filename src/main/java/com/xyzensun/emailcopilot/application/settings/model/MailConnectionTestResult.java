package com.xyzensun.emailcopilot.application.settings.model;

/** 连接探测是业务结果；失败也由 HTTP 200 返回。 */
public record MailConnectionTestResult(boolean ok, String message) {
}
