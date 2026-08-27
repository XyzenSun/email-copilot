package com.xyzensun.emailcopilot.domain.enums;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * 发件人规则类型（{@code DATABASE.md} §4.6）。
 *
 * <p><b>规则匹配的是 {@code message.from_authenticated_domain}，不是 From 字面值。</b>
 * DKIM 认证失败的邮件任何 {@code TRUST} 规则都不生效——否则伪造 From 就能绕过屏蔽、
 * 或冒充可信域名骗过垃圾判定。
 *
 * <p>域名模式只支持两种通配（应用按前缀解析，不用正则）：
 * {@code *.a.com} 恰好一层，{@code +.a.com} 任意层含裸域名。
 *
 * <p><b>规则变更不回溯已判定的邮件</b>：判定在收信时一次完成并写入 {@code message.category}，
 * 今天新增一条屏蔽规则，昨天收到的邮件不会变成 spam。界面必须写明这一点，
 * 否则用户会以为规则没生效而反复检查。
 */
public enum SenderRuleType implements IEnum<String> {

    BLOCK("block"),
    TRUST("trust");

    private final String value;

    SenderRuleType(String value) {
        this.value = value;
    }

    @Override
    public String getValue() {
        return value;
    }
}
