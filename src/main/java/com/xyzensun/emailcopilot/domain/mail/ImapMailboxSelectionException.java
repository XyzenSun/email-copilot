package com.xyzensun.emailcopilot.domain.mail;

/**
 * 服务器 mailbox 范围无法完整解析时的确定性失败。
 *
 * <p>异常只包含 mailbox 名与固定诊断，不携带主机、用户名、口令或协议原始响应，
 * 因而可安全转换为维护任务的用户可见错误。
 */
public class ImapMailboxSelectionException extends RuntimeException {

    public ImapMailboxSelectionException(String safeMessage) {
        super(safeMessage, null, false, false);
    }
}
