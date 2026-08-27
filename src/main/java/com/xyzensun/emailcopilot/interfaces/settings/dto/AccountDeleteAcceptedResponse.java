package com.xyzensun.emailcopilot.interfaces.settings.dto;

import com.xyzensun.emailcopilot.application.settings.model.AccountDeleteAccepted;

/** 删除账号受理响应同时返回不可逆影响面。 */
public record AccountDeleteAcceptedResponse(String taskId, long messageCount) {

    public static AccountDeleteAcceptedResponse from(AccountDeleteAccepted accepted) {
        return new AccountDeleteAcceptedResponse(accepted.taskId(), accepted.messageCount());
    }
}
