package com.xyzensun.emailcopilot.interfaces.conversation.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建一轮对话的请求。conversationId 为 null 时同请求新建对话。
 *
 * <p>userMessage 进模型前包裹为不可信内容，因此 toString 不展开值。
 */
public record TurnCreateRequest(
        Long conversationId,
        @NotBlank(message = "用户消息不能为空") String userMessage) {

    @Override
    public String toString() {
        return "TurnCreateRequest[conversationId=" + conversationId + ", userMessage=<已隐藏>]";
    }
}
