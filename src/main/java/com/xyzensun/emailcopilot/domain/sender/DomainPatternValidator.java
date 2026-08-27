package com.xyzensun.emailcopilot.domain.sender;

/**
 * 发件人规则域名模式的领域入口。
 *
 * <p>校验与规范化由 {@link DomainPattern} 一次完成，避免 Controller、应用服务和后续阶段 4
 * 流水线各自维护一份规则。返回值本身就是可持久化、可匹配的不可变值对象。
 */
public final class DomainPatternValidator {

    private DomainPatternValidator() {
    }

    /**
     * 校验并规范化模式。
     *
     * @throws IllegalArgumentException 不是 {@code a.com}/{@code *.a.com}/{@code +.a.com} 形式
     */
    public static DomainPattern validate(String rawPattern) {
        return DomainPattern.parse(rawPattern);
    }
}
