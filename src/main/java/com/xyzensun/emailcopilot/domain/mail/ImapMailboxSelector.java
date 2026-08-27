package com.xyzensun.emailcopilot.domain.mail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按已确认的 {@code INBOX + SPECIAL-USE \\Junk + 显式 fallback} 契约选择 mailbox。
 *
 * <p>只要服务器返回至少一个可选择的 {@code \\Junk}，配置中的 fallback 就完全不参与，
 * 避免旧配置意外扩大同步范围。服务器没有 {@code \\Junk} 时，每个 fallback 都必须能由
 * LIST 精确解析且可选择；缺失或错误时明确失败，不能把“只收了 INBOX”声称为完整成功。
 */
public final class ImapMailboxSelector {

    public static final String INBOX = "INBOX";

    private ImapMailboxSelector() {
    }

    public static List<String> select(
            Collection<ImapMailboxDescriptor> listedMailboxes,
            List<String> configuredFallbackMailboxes) {
        Map<String, ImapMailboxDescriptor> mailboxesByExactName = indexByExactName(listedMailboxes);
        ImapMailboxDescriptor inbox = listedMailboxes.stream()
                .filter(descriptor -> INBOX.equalsIgnoreCase(descriptor.fullName()))
                .findFirst()
                .orElseThrow(() -> new ImapMailboxSelectionException(
                        "IMAP 服务器未返回 INBOX，未执行不完整同步"));
        requireSelectable(inbox, INBOX);

        List<String> junkMailboxes = listedMailboxes.stream()
                .filter(ImapMailboxDescriptor::selectable)
                .filter(ImapMailboxDescriptor::junk)
                .map(ImapMailboxDescriptor::fullName)
                .filter(fullName -> !INBOX.equalsIgnoreCase(fullName))
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();

        Set<String> selected = new LinkedHashSet<>();
        selected.add(INBOX);
        if (!junkMailboxes.isEmpty()) {
            selected.addAll(junkMailboxes);
            return List.copyOf(selected);
        }

        if (configuredFallbackMailboxes == null || configuredFallbackMailboxes.isEmpty()) {
            throw new ImapMailboxSelectionException(
                    "服务器未声明可选择的 \\Junk，且未配置 imapFolders fallback，未执行不完整同步");
        }

        for (String configuredName : configuredFallbackMailboxes) {
            if (configuredName == null || configuredName.isBlank()) {
                throw new ImapMailboxSelectionException("imapFolders fallback 含空文件夹名称");
            }
            if (INBOX.equalsIgnoreCase(configuredName)) {
                continue;
            }
            ImapMailboxDescriptor descriptor = mailboxesByExactName.get(configuredName);
            if (descriptor == null) {
                throw new ImapMailboxSelectionException(
                        "imapFolders fallback 不存在或无法由服务器精确解析: " + configuredName);
            }
            requireSelectable(descriptor, configuredName);
            selected.add(descriptor.fullName());
        }

        List<String> sortedFallbacks = new ArrayList<>(selected);
        sortedFallbacks.remove(INBOX);
        sortedFallbacks.sort(Comparator.naturalOrder());
        sortedFallbacks.addFirst(INBOX);
        return List.copyOf(sortedFallbacks);
    }

    private static Map<String, ImapMailboxDescriptor> indexByExactName(
            Collection<ImapMailboxDescriptor> listedMailboxes) {
        if (listedMailboxes == null) {
            throw new ImapMailboxSelectionException("IMAP LIST 未返回 mailbox 列表");
        }
        Map<String, ImapMailboxDescriptor> result = new LinkedHashMap<>();
        for (ImapMailboxDescriptor descriptor : listedMailboxes) {
            if (descriptor != null) {
                result.putIfAbsent(descriptor.fullName(), descriptor);
            }
        }
        return result;
    }

    private static void requireSelectable(ImapMailboxDescriptor descriptor, String configuredName) {
        if (!descriptor.selectable()) {
            throw new ImapMailboxSelectionException(
                    "mailbox 不存在、不可选择或不包含邮件: " + configuredName);
        }
    }
}
