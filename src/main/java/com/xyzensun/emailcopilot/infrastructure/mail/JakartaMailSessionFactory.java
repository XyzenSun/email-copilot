package com.xyzensun.emailcopilot.infrastructure.mail;

import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MailAccount;
import jakarta.mail.Session;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * IMAP/SMTP 共用的 Jakarta Mail Session 配置。
 *
 * <p>993/465 使用隐式 TLS，其它端口强制 STARTTLS；失败后不换协议重试。IMAP 的
 * {@code peek=true} 是严格只读边界的一部分，确保正文 FETCH 使用 BODY.PEEK 而不设置
 * {@code \\Seen}。协议 debug 永远关闭，避免认证数据进入日志。
 */
@Component
public class JakartaMailSessionFactory {

    public static final int IMPLICIT_TLS_IMAP_PORT = 993;
    public static final int IMPLICIT_TLS_SMTP_PORT = 465;
    public static final int CONNECTION_TIMEOUT_MILLIS = 20_000;

    public ProtocolSession createImapSession(MailAccount account) {
        boolean implicitTls = account.getImapPort() == IMPLICIT_TLS_IMAP_PORT;
        String protocol = implicitTls ? "imaps" : "imap";
        Properties properties = baseProperties(protocol, implicitTls, CONNECTION_TIMEOUT_MILLIS);
        properties.setProperty("mail.imap.peek", "true");
        properties.setProperty("mail.imaps.peek", "true");
        return new ProtocolSession(protocol, Session.getInstance(properties));
    }

    public ProtocolSession createSmtpSession(MailAccount account) {
        return createSmtpSession(account, CONNECTION_TIMEOUT_MILLIS);
    }

    /**
     * 发信专用重载：超时可配，对接 {@code app_setting.smtp_timeout_seconds}（下限 5 秒）。
     *
     * <p>IMAP 侧维持原 20 秒不变——收信超时只影响同步器轮询节奏，发信超时则直接决定
     * {@code indeterminate} 语义（超时即无法证明副作用未开始）。Session 属性在
     * {@code getInstance} 时固化，构造后改 Properties 无效，必须在此处传入。
     */
    public ProtocolSession createSmtpSession(MailAccount account, int timeoutMillis) {
        boolean implicitTls = account.getSmtpPort() == IMPLICIT_TLS_SMTP_PORT;
        String protocol = implicitTls ? "smtps" : "smtp";
        Properties properties = baseProperties(protocol, implicitTls, timeoutMillis);
        properties.setProperty("mail." + protocol + ".auth", "true");
        return new ProtocolSession(protocol, Session.getInstance(properties));
    }

    private static Properties baseProperties(String protocol, boolean implicitTls, int timeoutMillis) {
        Properties properties = new Properties();
        String prefix = "mail." + protocol + ".";
        properties.setProperty("mail.debug", "false");
        properties.setProperty(prefix + "connectiontimeout", Integer.toString(timeoutMillis));
        properties.setProperty(prefix + "timeout", Integer.toString(timeoutMillis));
        properties.setProperty(prefix + "writetimeout", Integer.toString(timeoutMillis));
        properties.setProperty(prefix + "ssl.checkserveridentity", "true");
        if (implicitTls) {
            properties.setProperty(prefix + "ssl.enable", "true");
        } else {
            properties.setProperty(prefix + "starttls.enable", "true");
            properties.setProperty(prefix + "starttls.required", "true");
        }
        return properties;
    }

    public record ProtocolSession(String protocol, Session session) {
    }
}
