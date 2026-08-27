package com.xyzensun.emailcopilot.application.conversation.model;

/**
 * 创建一轮对话的请求。
 *
 * @param conversationId 对话 id；null 时同请求新建对话（design.md §5）
 * @param userMessage 用户原始输入；进模型前包裹为不可信内容
 */
public record TurnRequest(Long conversationId, String userMessage) {
}
