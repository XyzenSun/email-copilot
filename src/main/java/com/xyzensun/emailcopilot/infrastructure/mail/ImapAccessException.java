package com.xyzensun.emailcopilot.infrastructure.mail;

/**
 * 对上层暴露的脱敏 IMAP 失败。
 *
 * <p>不保留外部异常为 cause：Jakarta Mail 异常文本可能包含用户名、主机或服务器原始响应，
 * 而维护任务会记录未处理异常的完整堆栈。协议诊断仅用固定错误码与安全文案表达。
 */
public class ImapAccessException extends Exception {

    private final String errorCode;

    public ImapAccessException(String errorCode, String safeMessage) {
        super(safeMessage, null, false, false);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
