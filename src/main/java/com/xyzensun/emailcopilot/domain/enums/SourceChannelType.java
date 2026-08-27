package com.xyzensun.emailcopilot.domain.enums;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * 邮件来源通道，{@code message_source} 的 {@code FirstIngestWins} 载体
 * （{@code DATABASE.md} §3.3）。
 *
 * <p>{@code SMTP} 表示本系统发出：发信入库时记一条
 * {@code channel_type='smtp', is_canonical=true}，保持"每封邮件都有一条正典来源"这条不变式。
 *
 * <p>若服务商把副本放进 Sent 且 IMAP 后来读到，{@code uk (mail_account_id, message_id)}
 * 会挡住重复入库，只补插一条 {@code channel_type='imap', is_canonical=false}——
 * 现有约束原样复用，无需新逻辑，这也是选择 inbound/outbound 同表方案的额外收益。
 */
public enum SourceChannelType implements IEnum<String> {

    IMAP("imap"),
    SMTP("smtp");

    private final String value;

    SourceChannelType(String value) {
        this.value = value;
    }

    @Override
    public String getValue() {
        return value;
    }
}
