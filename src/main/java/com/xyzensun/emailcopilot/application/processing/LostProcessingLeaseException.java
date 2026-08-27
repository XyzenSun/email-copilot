package com.xyzensun.emailcopilot.application.processing;

/** 旧 worker 已失去 fencing 写入资格；这是正常取消，不携带邮件或 provider 内容。 */
public final class LostProcessingLeaseException extends RuntimeException {

    public LostProcessingLeaseException() {
        super("处理租约已失效");
    }
}
