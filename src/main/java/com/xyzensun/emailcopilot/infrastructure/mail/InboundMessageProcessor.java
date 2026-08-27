package com.xyzensun.emailcopilot.infrastructure.mail;

import com.xyzensun.emailcopilot.domain.mail.ParsedInboundMessage;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * 对一份远端原始流执行 bounded spool、MIME 解析和 DKIM/DNS 验证。
 *
 * <p>原始字节只从 IMAP 读取一次，随后 MIME4J 与 jDKIM 各自重新打开同一 owner-only
 * spool，避免重序列化破坏签名；不论成功或失败，临时文件都在返回前删除。
 */
@Component
public class InboundMessageProcessor {

    private final BoundedRawMessageSpool spooler;
    private final MimeMessageParser mimeMessageParser;
    private final DkimMessageVerifier dkimMessageVerifier;

    public InboundMessageProcessor(
            BoundedRawMessageSpool spooler,
            MimeMessageParser mimeMessageParser,
            DkimMessageVerifier dkimMessageVerifier) {
        this.spooler = spooler;
        this.mimeMessageParser = mimeMessageParser;
        this.dkimMessageVerifier = dkimMessageVerifier;
    }

    public ParsedInboundMessage process(InputStream remoteRawMessage)
            throws IOException, MessageContentRejectedException {
        try (BoundedRawMessageSpool.Spool spool = spooler.copyFrom(remoteRawMessage)) {
            ParsedInboundMessage parsed;
            try (InputStream mimeInput = spool.openStream()) {
                parsed = mimeMessageParser.parse(mimeInput);
            }
            DkimVerificationResult authentication;
            try (InputStream dkimInput = spool.openStream()) {
                authentication = dkimMessageVerifier.verify(
                        dkimInput, parsed.fromAddressDomain());
            }
            return parsed.withAuthentication(new ParsedInboundMessage.DkimAuthentication(
                    authentication.passed(), authentication.authenticatedDomain()));
        }
    }
}
