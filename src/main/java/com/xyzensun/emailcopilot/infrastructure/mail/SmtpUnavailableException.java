package com.xyzensun.emailcopilot.infrastructure.mail;

/**
 * SMTP 连接阶段失败（连不上服务器、认证失败），<b>未提交任何数据、可安全重试</b>。
 *
 * <p>与 {@link SmtpSendOutcome} 的三态分界：连接阶段失败不产生 SmtpSendOutcome，
 * 而是由本异常向上传播，应用服务映射为 503 {@code SMTP_UNAVAILABLE}。
 *
 * <p>不挂接原始异常的 message：SMTP/IMAP 的错误信息里可能带用户名或主机名，
 * 而 {@code detail} 会进入错误响应与日志。固定消息 + 不挂接原始异常，
 * 与 {@code RealMailboxTestClient} 的异常处理约定一致。
 */
public class SmtpUnavailableException extends RuntimeException {

    public SmtpUnavailableException() {
        super("SMTP 连接失败，未提交任何数据");
    }
}
