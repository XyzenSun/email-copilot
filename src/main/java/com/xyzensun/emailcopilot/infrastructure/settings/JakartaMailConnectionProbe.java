package com.xyzensun.emailcopilot.infrastructure.settings;

import com.xyzensun.emailcopilot.infrastructure.mail.JakartaMailSessionFactory;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MailAccount;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;

/**
 * 基于 Jakarta Mail 的真实 IMAP/SMTP 探测。
 *
 * <p>当前接口没有 TLS 模式字段，因此按已确认的端口约定：993/465 使用隐式 TLS，
 * 其它端口强制 STARTTLS。失败后不会换协议重试，避免把错误口令向同一服务器提交两次。
 *
 * <p>SMTP 只调用 {@link Transport#connect(String, int, String, String)} 并关闭连接，
 * 该过程执行 EHLO、STARTTLS、AUTH、QUIT；绝不构造邮件，也绝不调用
 * {@link Transport#sendMessage(jakarta.mail.Message, jakarta.mail.Address[])}。
 */
@Component
public class JakartaMailConnectionProbe implements MailConnectionProbe {

    private final JakartaMailSessionFactory sessionFactory;

    public JakartaMailConnectionProbe(JakartaMailSessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    @Override
    public ProbeResult testImap(MailAccount account, String password) {
        JakartaMailSessionFactory.ProtocolSession protocolSession =
                sessionFactory.createImapSession(account);

        try (Store store = protocolSession.session().getStore(protocolSession.protocol())) {
            store.connect(account.getImapHost(), account.getImapPort(), account.getImapUsername(), password);
            Folder inbox = store.getFolder("INBOX");
            if (!inbox.exists()) {
                return new ProbeResult(true, "登录成功，服务器未提供 INBOX 文件夹");
            }
            try (inbox) {
                // READ_ONLY 会发送 EXAMINE，不会改变已读标记，也不会执行 STORE/DELETE/EXPUNGE。
                inbox.open(Folder.READ_ONLY);
                return new ProbeResult(true, "登录成功，INBOX 共 " + inbox.getMessageCount() + " 封");
            }
        } catch (Exception ex) {
            return failedResult(ex);
        }
    }

    @Override
    public ProbeResult testSmtp(MailAccount account, String password) {
        JakartaMailSessionFactory.ProtocolSession protocolSession =
                sessionFactory.createSmtpSession(account);

        try (Transport transport = protocolSession.session().getTransport(protocolSession.protocol())) {
            transport.connect(account.getSmtpHost(), account.getSmtpPort(), account.getSmtpUsername(), password);
            return new ProbeResult(true, "SMTP 认证成功，未发送测试邮件");
        } catch (Exception ex) {
            return failedResult(ex);
        }
    }

    /** 外部异常原文可能回显用户名或 token，只按异常类别给固定诊断。 */
    private static ProbeResult failedResult(Exception exception) {
        if (hasCause(exception, AuthenticationFailedException.class)) {
            return new ProbeResult(false, "认证失败，请检查用户名和凭据");
        }
        if (hasCause(exception, SocketTimeoutException.class)) {
            return new ProbeResult(false, "连接超时，请检查服务器地址、端口与网络");
        }
        if (hasCause(exception, MessagingException.class)) {
            return new ProbeResult(false, "连接失败，请检查服务器地址、端口、TLS 与账号配置");
        }
        return new ProbeResult(false, "连接探测失败，请检查邮箱服务器配置");
    }

    private static boolean hasCause(Throwable exception, Class<? extends Throwable> expectedType) {
        Throwable current = exception;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
