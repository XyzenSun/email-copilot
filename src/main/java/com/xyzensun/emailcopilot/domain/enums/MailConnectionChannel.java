package com.xyzensun.emailcopilot.domain.enums;

/**
 * 邮箱连接探测支持的通道。
 */
public enum MailConnectionChannel {

    IMAP("imap"),
    SMTP("smtp");

    private final String value;

    MailConnectionChannel(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static MailConnectionChannel fromValue(String value) {
        for (MailConnectionChannel channel : values()) {
            if (channel.value.equals(value)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("不支持的邮箱连接通道");
    }
}
