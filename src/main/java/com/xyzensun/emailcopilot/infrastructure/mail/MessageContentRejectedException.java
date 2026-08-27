package com.xyzensun.emailcopilot.infrastructure.mail;

/**
 * 一封消息违反固定 MIME 安全边界后的确定性拒绝。
 *
 * <p>调用方可以把对应 UID 视作终态并越过，避免毒邮件永久阻塞 mailbox；异常只携带
 * 稳定错误码和固定文案，不携带 Subject、正文、文件名或 parser 原始诊断。
 */
public class MessageContentRejectedException extends Exception {

    private final String errorCode;

    public MessageContentRejectedException(String errorCode, String safeMessage) {
        super(safeMessage, null, false, false);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
