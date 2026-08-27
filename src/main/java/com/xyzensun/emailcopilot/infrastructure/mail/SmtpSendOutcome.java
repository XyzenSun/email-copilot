package com.xyzensun.emailcopilot.infrastructure.mail;

/**
 * SMTP 发信三态结果（design.md §2.3）。
 *
 * <p>三态区分按异常类型与发生阶段判断，非简单按 {@code SendFailedException}：
 * <ul>
 *   <li>{@link Status#SUCCEEDED} —— {@code sendMessage} 正常返回，SMTP 250 受理。</li>
 *   <li>{@link Status#FAILED} —— 明确拒绝（5xx）或连接前失败。消息确定未提交或被拒。</li>
 *   <li>{@link Status#INDETERMINATE} —— 连接成功后的超时/断开，<b>不能排除服务器已受理</b>。
 *       此时不入库、不自动重发。</li>
 * </ul>
 *
 * <p>连接阶段失败（{@code MailConnectException}）由 {@link SmtpUnavailableException} 单独抛出，
 * 应用服务映射为 503 {@code SMTP_UNAVAILABLE}（未提交数据、可安全重试），
 * 与 200+failed（服务器明确拒绝）分界。
 *
 * @param status       发信终态
 * @param serverMessage SMTP 最终响应原文或本地错误描述，界面直接显示
 * @param rfcMessageId  写入邮件头的 RFC Message-ID（{@code <uuid@domain>}），outbound 入库去重键
 */
public record SmtpSendOutcome(
        Status status,
        String serverMessage,
        String rfcMessageId) {

    public enum Status {
        SUCCEEDED,
        FAILED,
        INDETERMINATE
    }

    public static SmtpSendOutcome succeeded(String serverMessage, String rfcMessageId) {
        return new SmtpSendOutcome(Status.SUCCEEDED, serverMessage, rfcMessageId);
    }

    public static SmtpSendOutcome failed(String serverMessage, String rfcMessageId) {
        return new SmtpSendOutcome(Status.FAILED, serverMessage, rfcMessageId);
    }

    public static SmtpSendOutcome indeterminate(String serverMessage, String rfcMessageId) {
        return new SmtpSendOutcome(Status.INDETERMINATE, serverMessage, rfcMessageId);
    }
}
