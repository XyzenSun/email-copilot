package com.xyzensun.emailcopilot.domain.sender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 发件人规则的域名模式（{@code CONTEXT.md}「域名模式」）。
 *
 * <p>匹配按 {@code .} 手工分段，不把用户输入编译成正则表达式。这样模式的能力严格限定为：
 * 裸域名（精准匹配）、{@code *} 恰好一层、{@code +} 任意层且包含裸域名；不会因正则元字符
 * 或灾难性回溯扩大规则的影响面。
 *
 * <p>实例在创建时已经规范化并验证，可直接把 {@link #value()} 落库。候选值应当是 DKIM
 * 校验后得到的已认证域名，而不是不可信的 From 字面值；本类型只负责域名模式本身，
 * 不替代发件人认证边界。
 */
public final class DomainPattern {

    private static final int MAXIMUM_DNS_LABEL_LENGTH = 63;
    private static final int MAXIMUM_DOMAIN_LENGTH = 253;

    private final String value;
    private final MatchMode matchMode;
    private final List<String> suffixLabels;

    private DomainPattern(String value, MatchMode matchMode, List<String> suffixLabels) {
        this.value = value;
        this.matchMode = matchMode;
        this.suffixLabels = suffixLabels;
    }

    /**
     * 解析并规范化一个模式。
     *
     * <p>只去掉首尾空白并转为小写；不会修补连续点、非法连字符或中间空白，避免用户输入
     * 与实际保存值悄悄变成两种不同含义。三种形式：裸域名 {@code a.com}（只匹配该域本身）、
     * {@code *.a.com}（恰好一层子域）、{@code +.a.com}（裸域 + 任意层子域）。
     *
     * @throws IllegalArgumentException 模式为空或不符合 ASCII DNS label / 通配规则
     */
    public static DomainPattern parse(String rawPattern) {
        if (rawPattern == null) {
            throw new IllegalArgumentException("域名模式不能为空");
        }

        String normalizedPattern = rawPattern.strip().toLowerCase(Locale.ROOT);
        if (normalizedPattern.isEmpty()) {
            throw new IllegalArgumentException("域名模式不能为空");
        }

        MatchMode matchMode;
        String domainPart;
        if (normalizedPattern.startsWith("*.")) {
            matchMode = MatchMode.EXACTLY_ONE_PREFIX_LABEL;
            domainPart = normalizedPattern.substring(2);
        } else if (normalizedPattern.startsWith("+.")) {
            matchMode = MatchMode.ZERO_OR_MORE_PREFIX_LABELS;
            domainPart = normalizedPattern.substring(2);
        } else {
            // 不带通配前缀的裸域名是精准匹配：只命中 a.com 本身，不覆盖任何子域。
            // 与 *.a.com（恰好一层）、+.a.com（含裸域任意层）三档语义严格区分。
            matchMode = MatchMode.EXACT_DOMAIN;
            domainPart = normalizedPattern;
        }

        List<String> domainLabels = parseDomainLabels(domainPart);
        return new DomainPattern(normalizedPattern, matchMode, domainLabels);
    }

    /** 已规范化、可直接持久化的模式。 */
    public String value() {
        return value;
    }

    /**
     * 判断已认证域名是否命中。
     *
     * <p>候选域名来自外部邮件解析链，格式异常时返回 {@code false} 而不是让一封邮件中断整条
     * 流水线。域名比较大小写不敏感，但仍拒绝空 label、通配符和非 DNS 字符。
     */
    public boolean matches(String authenticatedDomain) {
        List<String> authenticatedDomainLabels = tryParseAuthenticatedDomain(authenticatedDomain);
        if (authenticatedDomainLabels == null) {
            return false;
        }

        int prefixLabelCount = authenticatedDomainLabels.size() - suffixLabels.size();
        boolean prefixLengthMatches = switch (matchMode) {
            case EXACT_DOMAIN -> prefixLabelCount == 0;
            case EXACTLY_ONE_PREFIX_LABEL -> prefixLabelCount == 1;
            case ZERO_OR_MORE_PREFIX_LABELS -> prefixLabelCount >= 0;
        };
        return prefixLengthMatches && suffixMatches(authenticatedDomainLabels);
    }

    private boolean suffixMatches(List<String> candidateLabels) {
        if (candidateLabels.size() < suffixLabels.size()) {
            return false;
        }
        int suffixStart = candidateLabels.size() - suffixLabels.size();
        for (int index = 0; index < suffixLabels.size(); index++) {
            if (!suffixLabels.get(index).equals(candidateLabels.get(suffixStart + index))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> tryParseAuthenticatedDomain(String authenticatedDomain) {
        if (authenticatedDomain == null) {
            return null;
        }
        String normalizedDomain = authenticatedDomain.strip().toLowerCase(Locale.ROOT);
        try {
            return parseDomainLabels(normalizedDomain);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * 手工扫描分段，刻意不使用 {@code String.split} / 正则。
     *
     * <p>除字符集外也校验 DNS label 的首尾连字符与长度上限，防止数据库接受一个后续
     * DKIM/DNS 组件永远不可能产生的模式。
     */
    private static List<String> parseDomainLabels(String domain) {
        if (domain.isEmpty() || domain.length() > MAXIMUM_DOMAIN_LENGTH) {
            throw new IllegalArgumentException("域名长度非法");
        }

        List<String> labels = new ArrayList<>();
        int labelStart = 0;
        for (int index = 0; index <= domain.length(); index++) {
            if (index < domain.length() && domain.charAt(index) != '.') {
                continue;
            }
            if (index == labelStart) {
                throw new IllegalArgumentException("域名不能包含空 label");
            }

            String label = domain.substring(labelStart, index);
            validateLabel(label);
            labels.add(label);
            labelStart = index + 1;
        }
        return List.copyOf(labels);
    }

    private static void validateLabel(String label) {
        if (label.length() > MAXIMUM_DNS_LABEL_LENGTH
                || label.charAt(0) == '-'
                || label.charAt(label.length() - 1) == '-') {
            throw new IllegalArgumentException("DNS label 长度或连字符位置非法");
        }

        for (int index = 0; index < label.length(); index++) {
            char currentCharacter = label.charAt(index);
            boolean isAsciiLetter = currentCharacter >= 'a' && currentCharacter <= 'z';
            boolean isDigit = currentCharacter >= '0' && currentCharacter <= '9';
            if (!isAsciiLetter && !isDigit && currentCharacter != '-') {
                throw new IllegalArgumentException("DNS label 含非法字符");
            }
        }
    }

    private enum MatchMode {
        /** 精准匹配裸域本身，不含任何子域。 */
        EXACT_DOMAIN,
        EXACTLY_ONE_PREFIX_LABEL,
        ZERO_OR_MORE_PREFIX_LABELS
    }
}
