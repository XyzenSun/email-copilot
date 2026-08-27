package com.xyzensun.emailcopilot.domain.conversation;

import com.xyzensun.emailcopilot.domain.Recipients;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 提案参数的规范化指纹（{@code DATABASE.md} §5.4 兜底幂等键）。
 *
 * <p>由应用对规范化后的参数计算（收件人排序、目标 ID 排序、正文归一化），
 * 保证"语义相同"的提案命中同一键。模型重试可能产生新的 tool call ID，
 * 此时主幂等键漏掉重复提案，兜底键靠 canonical payload hash 拦截。
 *
 * <p><b>只覆盖高风险动作</b>（send_email / local_delete）：save_draft 的重复提案是无害的。
 */
public final class CanonicalPayloadHasher {

    private CanonicalPayloadHasher() {
    }

    /**
     * 发信/草稿提案的规范化指纹。
     *
     * <p>归一化规则：收件人按 to→cc→bcc 各自排序后拼接，正文去首尾空白并统一换行，
     * 主题与发信账号 id 一并纳入。同一语义的提案产生相同 hash。
     */
    public static String forSendEmailOrDraft(
            String actionType,
            long fromMailAccountId,
            Long inReplyToMessageId,
            Recipients recipients,
            String subject,
            String bodyText) {
        StringBuilder canonical = new StringBuilder(actionType);
        canonical.append('|').append(fromMailAccountId);
        canonical.append('|').append(inReplyToMessageId == null ? "" : inReplyToMessageId);
        // 收件人排序：to→cc→bc 各自排序后拼接，保证顺序无关。
        canonical.append('|').append(sortedAddresses(recipients.to()));
        canonical.append('|').append(sortedAddresses(recipients.cc()));
        canonical.append('|').append(sortedAddresses(recipients.bcc()));
        canonical.append('|').append(normalizeText(subject));
        canonical.append('|').append(normalizeText(bodyText));
        return sha256Base64(canonical.toString());
    }

    /**
     * 本地删除提案的规范化指纹。
     *
     * <p>目标 id 排序去重后拼接，保证"删除这 3 封"与"删除同样 3 封但顺序不同"命中同一键。
     */
    public static String forLocalDelete(List<Long> targetMessageIds) {
        List<Long> sorted = new ArrayList<>(targetMessageIds);
        sorted.sort(Long::compare);
        StringBuilder canonical = new StringBuilder("local_delete");
        for (Long id : sorted) {
            canonical.append('|').append(id);
        }
        return sha256Base64(canonical.toString());
    }

    private static String sortedAddresses(List<String> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return "";
        }
        List<String> sorted = new ArrayList<>(addresses);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return String.join(",", sorted);
    }

    private static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        // 统一换行与去首尾空白：CRLF → LF，CR → LF，连续空白折叠。
        return text.replace("\r\n", "\n").replace("\r", "\n").strip();
    }

    private static String sha256Base64(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
