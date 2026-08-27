package com.xyzensun.emailcopilot.infrastructure.mail;

import com.xyzensun.emailcopilot.domain.mail.ImapMailboxDescriptor;
import com.xyzensun.emailcopilot.domain.mail.ImapMailboxSelectionException;
import com.xyzensun.emailcopilot.domain.mail.ImapMailboxSelector;
import com.xyzensun.emailcopilot.domain.mail.ImapUidSyncPlan;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MailAccount;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPMessage;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Eclipse Angus Mail 的真实、严格只读 IMAP 协议适配器。
 *
 * <p>生产 API 只暴露 LIST、READ_ONLY、UID 快照/FETCH 和原始 MIME 的 peek 流；没有设置
 * flags、复制、移动、删除或 expunge 的方法。调用方必须使用 try-with-resources 依次关闭
 * {@link Connection}、{@link Mailbox} 与 {@link MessageHandle#openRawMimeStream()}。
 */
@Component
public class AngusImapMailboxClient {

    private final JakartaMailSessionFactory sessionFactory;

    public AngusImapMailboxClient(JakartaMailSessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    /** 建立真实 TLS/STARTTLS + AUTH 连接；异常只转换为不含凭据的稳定诊断。 */
    public Connection connect(MailAccount account, String password) throws ImapAccessException {
        JakartaMailSessionFactory.ProtocolSession protocolSession =
                sessionFactory.createImapSession(account);
        Store store = null;
        try {
            store = protocolSession.session().getStore(protocolSession.protocol());
            store.connect(account.getImapHost(), account.getImapPort(), account.getImapUsername(), password);
            return new Connection(store);
        } catch (AuthenticationFailedException ex) {
            closeQuietly(store);
            throw new ImapAccessException("IMAP_AUTHENTICATION_FAILED", "IMAP 认证失败，请检查账号凭据");
        } catch (Exception ex) {
            closeQuietly(store);
            throw new ImapAccessException(
                    "IMAP_CONNECTION_FAILED", "IMAP 连接失败，请检查服务器、端口、TLS 与网络");
        }
    }

    public static final class Connection implements AutoCloseable {

        private final Store store;

        private Connection(Store store) {
            this.store = store;
        }

        /**
         * 读取 LIST/SPECIAL-USE 后按产品范围选择 mailbox。始终显式补读 INBOX，避免某些
         * 服务器的通配 LIST 省略它；补读不会扩大到其它普通 mailbox。
         */
        public List<String> discoverActiveMailboxes(List<String> configuredFallbackMailboxes)
                throws ImapAccessException {
            try {
                Map<String, ImapMailboxDescriptor> descriptorsByName = new LinkedHashMap<>();
                Folder defaultFolder = store.getDefaultFolder();
                for (Folder folder : defaultFolder.list("*")) {
                    ImapMailboxDescriptor descriptor = toDescriptor(folder);
                    descriptorsByName.putIfAbsent(descriptor.fullName(), descriptor);
                }

                Folder inbox = store.getFolder(ImapMailboxSelector.INBOX);
                ImapMailboxDescriptor inboxDescriptor = toDescriptor(inbox);
                descriptorsByName.entrySet().removeIf(
                        entry -> ImapMailboxSelector.INBOX.equalsIgnoreCase(entry.getKey()));
                descriptorsByName.put(ImapMailboxSelector.INBOX, inboxDescriptor);
                return ImapMailboxSelector.select(
                        descriptorsByName.values(), configuredFallbackMailboxes);
            } catch (ImapAccessException ex) {
                throw ex;
            } catch (ImapMailboxSelectionException ex) {
                throw new ImapAccessException("IMAP_MAILBOX_SCOPE_INCOMPLETE", ex.getMessage());
            } catch (Exception ex) {
                throw new ImapAccessException(
                        "IMAP_LIST_FAILED", "IMAP mailbox 发现失败，未声称同步完整");
            }
        }

        /** 每个 mailbox 只能以 READ_ONLY 打开；打开失败不会切换 READ_WRITE 重试。 */
        public Mailbox openMailbox(String fullName) throws ImapAccessException {
            Folder folder = null;
            try {
                folder = store.getFolder(fullName);
                if (!folder.exists() || (folder.getType() & Folder.HOLDS_MESSAGES) == 0) {
                    throw new ImapAccessException(
                            "IMAP_MAILBOX_NOT_SELECTABLE", "mailbox 不存在或不可选择: " + fullName);
                }
                if (!(folder instanceof UIDFolder uidFolder)) {
                    throw new ImapAccessException(
                            "IMAP_UID_UNSUPPORTED", "mailbox 不支持 UID 增量同步: " + fullName);
                }
                folder.open(Folder.READ_ONLY);
                if (folder.getMode() != Folder.READ_ONLY) {
                    closeQuietly(folder);
                    throw new ImapAccessException(
                            "IMAP_READ_ONLY_REQUIRED", "mailbox 无法按只读方式打开: " + fullName);
                }
                return new Mailbox(folder, uidFolder);
            } catch (ImapAccessException ex) {
                closeQuietly(folder);
                throw ex;
            } catch (Exception ex) {
                closeQuietly(folder);
                throw new ImapAccessException(
                        "IMAP_MAILBOX_OPEN_FAILED", "mailbox 只读打开失败: " + fullName);
            }
        }

        @Override
        public void close() {
            closeQuietly(store);
        }

        private static ImapMailboxDescriptor toDescriptor(Folder folder)
                throws MessagingException, ImapAccessException {
            Collection<String> attributes = folder instanceof IMAPFolder imapFolder
                    ? Arrays.asList(imapFolder.getAttributes())
                    : List.of();
            boolean exists = folder.exists();
            int type = exists ? folder.getType() : 0;
            String fullName = ImapMailboxSelector.INBOX.equalsIgnoreCase(folder.getFullName())
                    ? ImapMailboxSelector.INBOX
                    : folder.getFullName();
            if (fullName == null || fullName.isBlank()) {
                throw new ImapAccessException("IMAP_LIST_INVALID", "IMAP LIST 返回了空 mailbox 名称");
            }
            return new ImapMailboxDescriptor(
                    fullName, java.util.Set.copyOf(attributes), exists,
                    (type & Folder.HOLDS_MESSAGES) != 0);
        }
    }

    public static final class Mailbox implements AutoCloseable {

        private final Folder folder;
        private final UIDFolder uidFolder;

        private Mailbox(Folder folder, UIDFolder uidFolder) {
            this.folder = folder;
            this.uidFolder = uidFolder;
        }

        /** UIDNEXT 只形成打开瞬间的上界；未知时退化为最后一封实际消息的 UID。 */
        public MailboxSnapshot snapshot() throws ImapAccessException {
            try {
                long uidValidity = uidFolder.getUIDValidity();
                long uidNext = uidFolder.getUIDNext();
                long lastExistingUid = 0;
                if (uidNext == ImapUidSyncPlan.UNKNOWN_UID_NEXT && folder.getMessageCount() > 0) {
                    lastExistingUid = uidFolder.getUID(folder.getMessage(folder.getMessageCount()));
                }
                long upperUid = ImapUidSyncPlan.snapshotUpperUid(uidNext, lastExistingUid);
                return new MailboxSnapshot(uidValidity, uidNext, upperUid);
            } catch (Exception ex) {
                throw new ImapAccessException(
                        "IMAP_UID_SNAPSHOT_FAILED", "无法建立 mailbox UID 快照: " + folder.getFullName());
            }
        }

        /**
         * 获取计划范围内的真实 UID。bootstrap 先用 SINCE 粗筛，再由计划按完整 INTERNALDATE
         * 精确过滤；正常增量直接执行 UID 范围读取。结果严格按 UID 升序。
         */
        public List<MessageHandle> listMessages(ImapUidSyncPlan plan) throws ImapAccessException {
            if (!plan.hasUidRangeToInspect()) {
                return List.of();
            }
            try {
                Message[] serverMessages;
                if (plan.bootstrap()) {
                    serverMessages = folder.search(new ReceivedDateTerm(
                            ComparisonTerm.GE, Date.from(plan.initialSyncSince().toInstant())));
                } else {
                    serverMessages = uidFolder.getMessagesByUID(
                            plan.firstUid(), plan.snapshotUpperUid());
                }

                FetchProfile fetchProfile = new FetchProfile();
                fetchProfile.add(FetchProfile.Item.ENVELOPE);
                fetchProfile.add(UIDFolder.FetchProfileItem.UID);
                folder.fetch(serverMessages, fetchProfile);

                List<LoadedMessage> loadedMessages = new ArrayList<>(serverMessages.length);
                for (Message message : serverMessages) {
                    long uid = uidFolder.getUID(message);
                    Date internalDate = message.getReceivedDate();
                    OffsetDateTime receivedAt = internalDate == null
                            ? null
                            : OffsetDateTime.ofInstant(internalDate.toInstant(), ZoneOffset.UTC);
                    loadedMessages.add(new LoadedMessage(uid, receivedAt, message));
                }

                List<ImapUidSyncPlan.MessageCandidate> selectedMetadata = plan.selectCandidates(
                        loadedMessages.stream()
                                .map(loaded -> new ImapUidSyncPlan.MessageCandidate(
                                        loaded.uid(), loaded.internalDate()))
                                .toList());
                Map<Long, LoadedMessage> loadedByUid = new LinkedHashMap<>();
                loadedMessages.forEach(message -> loadedByUid.put(message.uid(), message));
                return selectedMetadata.stream()
                        .map(candidate -> loadedByUid.get(candidate.uid()))
                        .filter(java.util.Objects::nonNull)
                        .sorted(Comparator.comparingLong(LoadedMessage::uid))
                        .map(message -> new MessageHandle(
                                message.uid(), message.internalDate(), message.message()))
                        .toList();
            } catch (Exception ex) {
                throw new ImapAccessException(
                        "IMAP_UID_FETCH_FAILED", "mailbox UID FETCH 失败: " + folder.getFullName());
            }
        }

        public String fullName() {
            return folder.getFullName();
        }

        @Override
        public void close() {
            closeQuietly(folder);
        }
    }

    public record MailboxSnapshot(long uidValidity, long uidNext, long snapshotUpperUid) {

        public MailboxSnapshot {
            if (uidValidity < 1 || uidValidity > ImapUidSyncPlan.MAX_UID) {
                throw new IllegalArgumentException("uidValidity 超出 IMAP 范围");
            }
        }
    }

    /**
     * 一封远端消息的只读句柄。原始 RFC 5322 流必须在所属 Mailbox 关闭前消费并关闭。
     */
    public static final class MessageHandle {

        private final long uid;
        private final OffsetDateTime internalDate;
        private final Message message;

        private MessageHandle(long uid, OffsetDateTime internalDate, Message message) {
            this.uid = uid;
            this.internalDate = internalDate;
            this.message = message;
        }

        public long uid() {
            return uid;
        }

        public OffsetDateTime internalDate() {
            return internalDate;
        }

        /** Angus 的 peek 标志与 Session 的 mail.*.peek=true 双重保证 BODY.PEEK 读取。 */
        public InputStream openRawMimeStream() throws ImapAccessException {
            try {
                if (!(message instanceof IMAPMessage imapMessage)) {
                    throw new ImapAccessException(
                            "IMAP_MESSAGE_TYPE_UNSUPPORTED", "服务器消息类型不支持原始 MIME peek 读取");
                }
                imapMessage.setPeek(true);
                return imapMessage.getMimeStream();
            } catch (ImapAccessException ex) {
                throw ex;
            } catch (MessagingException ex) {
                throw new ImapAccessException("IMAP_BODY_FETCH_FAILED", "原始 MIME 只读获取失败");
            }
        }
    }

    private record LoadedMessage(long uid, OffsetDateTime internalDate, Message message) {
    }

    private static void closeQuietly(Folder folder) {
        if (folder == null || !folder.isOpen()) {
            return;
        }
        try {
            // expunge=false 是只读关闭的显式防线，绝不调用无参 expunge。
            folder.close(false);
        } catch (MessagingException ignored) {
            // 关闭失败不覆盖更早且更有诊断价值的安全异常。
        }
    }

    private static void closeQuietly(Store store) {
        if (store == null) {
            return;
        }
        try {
            store.close();
        } catch (MessagingException ignored) {
            // 关闭失败不回显服务器原始响应，连接租约最终由 socket 超时释放。
        }
    }
}
