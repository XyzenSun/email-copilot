package com.xyzensun.emailcopilot.domain.mail;

import java.util.Locale;
import java.util.Set;

/**
 * IMAP LIST 返回的一项 mailbox 元数据。
 *
 * <p>属性按不区分大小写的 IMAP system flag 规范化；完整 mailbox 名保持服务器原值。
 * 来源角色只用于选择接收范围，不会映射到 {@code message.category}。
 */
public record ImapMailboxDescriptor(
        String fullName,
        Set<String> attributes,
        boolean exists,
        boolean holdsMessages) {

    public ImapMailboxDescriptor {
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("mailbox 完整名称不能为空");
        }
        attributes = attributes == null
                ? Set.of()
                : attributes.stream()
                        .map(attribute -> attribute.toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public boolean hasAttribute(String attribute) {
        return attribute != null && attributes.contains(attribute.toLowerCase(Locale.ROOT));
    }

    public boolean selectable() {
        return exists && holdsMessages && !hasAttribute("\\Noselect");
    }

    public boolean junk() {
        return hasAttribute("\\Junk");
    }
}
