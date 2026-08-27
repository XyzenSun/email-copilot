package com.xyzensun.emailcopilot.domain.mail;

import com.xyzensun.emailcopilot.domain.AttachmentMeta;
import com.xyzensun.emailcopilot.domain.Recipients;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * MIME/DKIM 层交给入库应用服务的不可变数据。
 *
 * <p>不携带 Jakarta Mail Message、凭据、打开的 stream 或临时文件路径，避免基础设施对象
 * 穿透事务边界。主题、显示名和正文均是不可信纯文本，日志不得打印本 record。
 */
public record ParsedInboundMessage(
        String messageId,
        boolean syntheticMessageId,
        String fingerprint,
        String fromDisplay,
        String fromAddress,
        String fromAddressDomain,
        String fromAuthenticatedDomain,
        Recipients recipients,
        String subject,
        String baseSubject,
        OffsetDateTime sentAt,
        String bodyText,
        List<String> references,
        List<AttachmentMeta> attachments,
        boolean dkimPassed) {

    public ParsedInboundMessage {
        recipients = recipients == null ? Recipients.empty() : recipients;
        references = references == null ? List.of() : List.copyOf(references);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** MIME 解析与 DKIM DNS 校验分两次读取同一 spool，认证结果最后不可变地合并。 */
    public ParsedInboundMessage withAuthentication(DkimAuthentication authentication) {
        boolean aligned = authentication != null
                && authentication.passed()
                && authentication.authenticatedDomain() != null
                && authentication.authenticatedDomain().equalsIgnoreCase(fromAddressDomain);
        return new ParsedInboundMessage(
                messageId,
                syntheticMessageId,
                fingerprint,
                fromDisplay,
                fromAddress,
                fromAddressDomain,
                aligned ? fromAddressDomain : null,
                recipients,
                subject,
                baseSubject,
                sentAt,
                bodyText,
                references,
                attachments,
                aligned);
    }

    @Override
    public String toString() {
        return "ParsedInboundMessage[messageId=<已隐藏>, content=<已隐藏>, references="
                + references.size() + ", attachments=" + attachments.size()
                + ", dkimPassed=" + dkimPassed + "]";
    }

    /** 领域层只需要知道认证是否通过及对齐后的域，不依赖 jDKIM 类型。 */
    public record DkimAuthentication(boolean passed, String authenticatedDomain) {
    }
}
