package com.xyzensun.emailcopilot.application.mail;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Attachment;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Message;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.AttachmentMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MessageMapper;
import com.xyzensun.emailcopilot.infrastructure.search.LuceneMailIndex;
import com.xyzensun.emailcopilot.infrastructure.search.SearchIndexDocument;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** PostgreSQL 当前邮件事实与 Lucene 可重建投影之间的应用编排。 */
@Service
public class MailIndexService {

    private static final int ATTACHMENT_LOOKUP_BATCH_SIZE = 500;

    private final MessageMapper messageMapper;
    private final AttachmentMapper attachmentMapper;
    private final LuceneMailIndex mailIndex;
    private final Clock clock;

    public MailIndexService(
            MessageMapper messageMapper,
            AttachmentMapper attachmentMapper,
            LuceneMailIndex mailIndex,
            Clock clock) {
        this.messageMapper = messageMapper;
        this.attachmentMapper = attachmentMapper;
        this.mailIndex = mailIndex;
        this.clock = clock;
    }

    public void initialize() {
        if (mailIndex.hasExpectedMetadata()) {
            mailIndex.open();
        } else {
            rebuild();
        }
    }

    public void rebuild() {
        List<Message> messages = messageMapper.selectList(
                Wrappers.lambdaQuery(Message.class)
                        .isNull(Message::getDeletedAt)
                        .eq(Message::getPurged, false)
                        .orderByAsc(Message::getId));
        mailIndex.rebuild(toDocuments(messages));
    }

    public void refreshMessage(long messageId) {
        Message message = messageMapper.selectById(messageId);
        if (message == null
                || message.getDeletedAt() != null
                || Boolean.TRUE.equals(message.getPurged())) {
            mailIndex.deleteMessage(messageId);
            return;
        }
        boolean hasAttachment = attachmentMapper.selectCount(
                Wrappers.lambdaQuery(Attachment.class)
                        .eq(Attachment::getMessageIdPk, messageId)) > 0;
        mailIndex.updateDocument(toDocument(message, hasAttachment));
    }

    public void deleteMessage(long messageId) {
        mailIndex.deleteMessage(messageId);
    }

    public void deleteMessages(List<Long> messageIds) {
        mailIndex.deleteMessages(messageIds);
    }

    public void deleteMailAccount(long mailAccountId) {
        mailIndex.deleteMailAccount(mailAccountId);
    }

    public void commit() {
        mailIndex.commit();
    }

    public void replayRecentCreatedMessages() {
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(
                clock.instant(), ZoneOffset.UTC).minusMinutes(15);
        List<Message> recent = messageMapper.selectList(
                Wrappers.lambdaQuery(Message.class)
                        .ge(Message::getCreatedAt, cutoff)
                        .orderByAsc(Message::getId));
        recent.forEach(message -> refreshMessage(message.getId()));
    }

    public void reconcile() {
        List<Message> visibleMessages = messageMapper.selectList(
                Wrappers.lambdaQuery(Message.class)
                        .isNull(Message::getDeletedAt)
                        .eq(Message::getPurged, false)
                        .orderByAsc(Message::getId));
        Set<Long> postgresIds = new LinkedHashSet<>();
        visibleMessages.forEach(message -> postgresIds.add(message.getId()));
        Set<Long> luceneIds = mailIndex.documentIds();

        Set<Long> missing = new LinkedHashSet<>(postgresIds);
        missing.removeAll(luceneIds);
        if (!missing.isEmpty()) {
            Map<Long, Message> byId = new HashMap<>();
            visibleMessages.forEach(message -> byId.put(message.getId(), message));
            List<Message> missingMessages = missing.stream().map(byId::get).toList();
            toDocuments(missingMessages).forEach(mailIndex::updateDocument);
        }

        Set<Long> orphaned = new LinkedHashSet<>(luceneIds);
        orphaned.removeAll(postgresIds);
        orphaned.forEach(mailIndex::deleteMessage);
    }

    private List<SearchIndexDocument> toDocuments(List<Message> messages) {
        if (messages.isEmpty()) {
            return List.of();
        }
        Set<Long> idsWithAttachments = new HashSet<>();
        List<Long> messageIds = messages.stream().map(Message::getId).toList();
        for (int start = 0; start < messageIds.size(); start += ATTACHMENT_LOOKUP_BATCH_SIZE) {
            int end = Math.min(start + ATTACHMENT_LOOKUP_BATCH_SIZE, messageIds.size());
            List<Attachment> attachments = attachmentMapper.selectList(
                    Wrappers.lambdaQuery(Attachment.class)
                            .in(Attachment::getMessageIdPk, messageIds.subList(start, end))
                            .select(Attachment::getMessageIdPk));
            attachments.forEach(attachment -> idsWithAttachments.add(attachment.getMessageIdPk()));
        }
        List<SearchIndexDocument> result = new ArrayList<>(messages.size());
        for (Message message : messages) {
            result.add(toDocument(message, idsWithAttachments.contains(message.getId())));
        }
        return result;
    }

    private static SearchIndexDocument toDocument(Message message, boolean hasAttachment) {
        return new SearchIndexDocument(
                message.getId(),
                message.getMailAccountId(),
                message.getDirection(),
                message.getCategory(),
                message.getTags(),
                message.getReceivedAt(),
                message.getSubject(),
                message.getBodyText(),
                message.getFromDisplay(),
                hasAttachment);
    }
}
