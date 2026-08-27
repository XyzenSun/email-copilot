package com.xyzensun.emailcopilot.interfaces.conversation.dto;

/**
 * 删除对话的确认响应。DELETE /conversations/{id} 返 200 + 被删 id——
 * 删除不可逆，空 204 让调用方无法确认删了哪个，明文返回 id 更踏实。
 */
public record DeleteConversationResponse(long id) {
}
