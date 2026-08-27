package com.xyzensun.emailcopilot.application.send.model;

/**
 * 发信结果视图，供 Controller 构造 {@code SendResult} 响应。
 *
 * <p>三种结果全部返回 HTTP 200，读 {@code status} 分支。
 * {@code messageId} 仅 {@code succeeded} 时有值（outbound 邮件入库后的 DB id）。
 */
public record SendResultView(
        String status,
        Long messageId,
        String resultMessage) {
}
