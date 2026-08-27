package com.xyzensun.emailcopilot.infrastructure.mail;

import com.xyzensun.emailcopilot.domain.Recipients;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MailAccount;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SMTP 发信执行器：构造 MimeMessage + 显式 connect + sendMessage + 三态分类。
 *
 * <p><b>从不注册为 AI 工具</b>（ARCHITECTURE §6.2、DECISIONS §6）——对话 AI 只能创建
 * PendingAction，不能直接发信。两条发信路径（批准发信 + 用户直发）共用本组件。
 *
 * <p><b>凭据边界</b>：明文口令仅 {@code send()} 调用栈内，不进 Properties/Session/URL/日志。
 * 用显式 {@code transport.connect(host, port, username, password)}，不用 Authenticator
 * （与阶段4A IMAP {@code store.connect} 同模式）。
 *
 * <p><b>三态分类</b>（design.md §2.3）按异常类型与发生阶段判断：
 * <ul>
 *   <li>连接阶段失败（{@link MailConnectException} 等）→ 抛 {@link SmtpUnavailableException}，
 *       应用服务映射 503（未提交数据、可安全重试）。</li>
 *   <li>{@code sendMessage} 正常返回 → {@link SmtpSendOutcome.Status#SUCCEEDED}。</li>
 *   <li>{@link SendFailedException}（含 {@code SMTPSendFailedException}）→
 *       {@link SmtpSendOutcome.Status#FAILED}（服务器明确拒绝）。</li>
 *   <li>连接成功后的其它 {@link MessagingException} →
 *       {@link SmtpSendOutcome.Status#INDETERMINATE}（不能排除服务器已受理）。</li>
 * </ul>
 *
 * <p>异常处理不挂接原始异常的 message：SMTP 错误信息可能带用户名或主机名。
 * 固定消息 + 不挂接，与 {@code RealMailboxTestClient} 的约定一致。
 */
@Component
public class SmtpMailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailSender.class);

    private final JakartaMailSessionFactory sessionFactory;

    public SmtpMailSender(JakartaMailSessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    /**
     * 发送一封纯文本邮件。
     *
     * @param account          发信邮箱账号（SMTP 连接信息 + From 地址/署名）
     * @param smtpPassword     解密后的明文口令，仅本调用栈内使用
     * @param recipients       收件人（to/cc/bcc）
     * @param subject          主题（允许空字符串）
     * @param bodyText         纯文本正文
     * @param rfcMessageId     预生成的 RFC Message-ID（{@code <uuid@fromDomain>}）
     * @param inReplyToHeader  回复时原邮件的 Message-ID（含尖括号），新建邮件为 null
     * @param referencesHeader 回复时的 References 头值，新建邮件为 null
     * @param timeoutMillis    SMTP 超时毫秒（来自 {@code app_setting.smtp_timeout_seconds}，下限 5 秒）
     * @return 三态结果
     * @throws SmtpUnavailableException 连接阶段失败（未提交数据、可安全重试）→ 503
     */
    public SmtpSendOutcome send(
            MailAccount account,
            String smtpPassword,
            Recipients recipients,
            String subject,
            String bodyText,
            String rfcMessageId,
            String inReplyToHeader,
            String referencesHeader,
            int timeoutMillis) {

        var protocolSession = sessionFactory.createSmtpSession(account, timeoutMillis);
        var message = new ControlledMessageIdMessage(protocolSession.session(), rfcMessageId);
        try {
            populateMessage(message, account, recipients, subject, bodyText, inReplyToHeader, referencesHeader);
            message.saveChanges();
        } catch (MessagingException exception) {
            // MIME 构造失败是确定性的本地错误，消息从未提交。
            log.warn("MIME 构造失败: mailAccountId={}", account.getId());
            return SmtpSendOutcome.failed("邮件构造失败", rfcMessageId);
        }

        Transport transport;
        try {
            transport = protocolSession.session().getTransport(protocolSession.protocol());
        } catch (jakarta.mail.NoSuchProviderException exception) {
            // protocol 总是 smtp/smtps，provider 由 angus-mail 保证注册。
            throw new IllegalStateException("SMTP transport 不可用", exception);
        }
        try {
            // 连接阶段：任何失败都意味着未提交数据，可安全重试 → 503。
            try {
                transport.connect(
                        account.getSmtpHost(),
                        account.getSmtpPort(),
                        account.getSmtpUsername(),
                        smtpPassword);
            } catch (MessagingException connectException) {
                log.warn("SMTP 连接阶段失败: mailAccountId={}", account.getId());
                throw new SmtpUnavailableException();
            }
            // 发信阶段：按异常类型区分 failed 与 indeterminate。
            try {
                transport.sendMessage(message, message.getAllRecipients());
            } catch (MessagingException sendException) {
                log.warn("SMTP 发信异常: mailAccountId={} type={}",
                        account.getId(), sendException.getClass().getSimpleName());
                return classifySendPhaseException(sendException, rfcMessageId);
            }
            return SmtpSendOutcome.succeeded("SMTP 发信成功", rfcMessageId);
        } finally {
            try {
                transport.close();
            } catch (MessagingException closeException) {
                // close 失败不影响已确定的发信结果。
            }
        }
    }

    private static void populateMessage(
            ControlledMessageIdMessage message,
            MailAccount account,
            Recipients recipients,
            String subject,
            String bodyText,
            String inReplyToHeader,
            String referencesHeader) throws MessagingException {

        message.setFrom(fromAddress(account));
        setRecipients(message, recipients);
        if (subject != null) {
            message.setSubject(subject, StandardCharsets.UTF_8.name());
        }
        message.setText(bodyText != null ? bodyText : "", StandardCharsets.UTF_8.name());
        message.setSentDate(java.util.Date.from(java.time.Instant.now()));
        if (inReplyToHeader != null && !inReplyToHeader.isBlank()) {
            message.setHeader("In-Reply-To", inReplyToHeader);
        }
        if (referencesHeader != null && !referencesHeader.isBlank()) {
            message.setHeader("References", referencesHeader);
        }
    }

    private static InternetAddress fromAddress(MailAccount account) throws MessagingException {
        InternetAddress address = new InternetAddress(account.getEmailAddress());
        if (account.getDisplayName() != null && !account.getDisplayName().isBlank()) {
            try {
                address.setPersonal(account.getDisplayName(), StandardCharsets.UTF_8.name());
            } catch (java.io.UnsupportedEncodingException exception) {
                // UTF-8 是 JVM 规范保证可用的字符集，此分支不会到达。
                throw new IllegalStateException("UTF-8 不可用", exception);
            }
        }
        return address;
    }

    private static void setRecipients(ControlledMessageIdMessage message, Recipients recipients) throws MessagingException {
        List<InternetAddress> toAddresses = parseAddresses(recipients.to());
        if (!toAddresses.isEmpty()) {
            message.setRecipients(Message.RecipientType.TO, toAddresses.toArray(new InternetAddress[0]));
        }
        List<InternetAddress> ccAddresses = parseAddresses(recipients.cc());
        if (!ccAddresses.isEmpty()) {
            message.setRecipients(Message.RecipientType.CC, ccAddresses.toArray(new InternetAddress[0]));
        }
        List<InternetAddress> bccAddresses = parseAddresses(recipients.bcc());
        if (!bccAddresses.isEmpty()) {
            message.setRecipients(Message.RecipientType.BCC, bccAddresses.toArray(new InternetAddress[0]));
        }
    }

    private static List<InternetAddress> parseAddresses(List<String> addresses) throws MessagingException {
        List<InternetAddress> result = new ArrayList<>();
        for (String address : addresses) {
            if (address != null && !address.isBlank()) {
                result.add(new InternetAddress(address, true));
            }
        }
        return result;
    }

    /**
     * 发信阶段异常分类（design.md §2.3）。按异常类型判断，非简单按 SendFailedException：
     * <ul>
     *   <li>{@link SendFailedException}（含 {@code SMTPSendFailedException}）→ FAILED（服务器明确拒绝）。</li>
     *   <li>其它 {@link MessagingException} → INDETERMINATE（连接成功后的超时/断开，不能排除已受理）。</li>
     * </ul>
     *
     * <p>包级可见以供单元测试覆盖三态分类逻辑（indeterminate 真实服务无法稳定触发）。
     */
    static SmtpSendOutcome classifySendPhaseException(MessagingException exception, String rfcMessageId) {
        if (exception instanceof SendFailedException) {
            return SmtpSendOutcome.failed("SMTP 发信被拒", rfcMessageId);
        }
        return SmtpSendOutcome.indeterminate("SMTP 响应超时或连接中断", rfcMessageId);
    }
}
