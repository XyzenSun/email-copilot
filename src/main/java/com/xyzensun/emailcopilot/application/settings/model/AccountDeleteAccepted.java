package com.xyzensun.emailcopilot.application.settings.model;

/** 已受理的异步账号删除及受影响邮件数快照。 */
public record AccountDeleteAccepted(String taskId, long messageCount) {
}
