package com.xyzensun.emailcopilot.infrastructure.mail;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

/**
 * 固定 Message-ID 的 MimeMessage 子类。
 *
 * <p>默认 {@link MimeMessage#updateMessageID()} 会在 {@code saveChanges()} 时生成一个含本机名
 * 的随机 ID 并覆盖手动写入的值。发信前需要预知 Message-ID（outbound 入库去重键、收信后核对闭环），
 * 因此子类化重写该方法，让 {@code saveChanges()} 保留应用生成的 {@code <uuid@发送域名>}。
 *
 * <p>不在此处生成 ID：ID 由调用方在构造前生成并传入，保证发信组件、入库、收信核对三方拿到同一个值。
 */
public class ControlledMessageIdMessage extends MimeMessage {

    private final String controlledMessageId;

    public ControlledMessageIdMessage(Session session, String controlledMessageId) {
        super(session);
        if (controlledMessageId == null || controlledMessageId.isBlank()) {
            throw new IllegalArgumentException("controlledMessageId 不能为空");
        }
        this.controlledMessageId = controlledMessageId;
    }

    @Override
    protected void updateMessageID() {
        try {
            setHeader("Message-ID", controlledMessageId);
        } catch (jakarta.mail.MessagingException exception) {
            // setHeader 仅在 session 关闭等极端情况抛出；构造期 session 总是可用。
            throw new IllegalStateException("无法写入固定 Message-ID", exception);
        }
    }

    /** 返回构造时传入的固定 Message-ID，供调用方预知发出去的值。 */
    public String controlledMessageId() {
        return controlledMessageId;
    }
}
