package com.xyzensun.emailcopilot.application.mail;

import com.xyzensun.emailcopilot.domain.Recipients;
import com.xyzensun.emailcopilot.domain.enums.MessageDirection;
import com.xyzensun.emailcopilot.domain.enums.SourceChannelType;
import com.xyzensun.emailcopilot.domain.mail.BaseSubject;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.AppSetting;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MailAccount;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Message;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MessageMention;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MessageSource;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.AppSettingMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MailAccountMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MessageMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MessageMentionMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MessageSourceMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.ThreadNodeMapper;
import com.xyzensun.emailcopilot.infrastructure.search.SearchIndexUnavailableException;
import com.xyzensun.emailcopilot.interfaces.error.ApiError;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 发信成功后的 outbound 邮件入库与衔接（design.md §5）。
 *
 * <p>与收信入库（{@link IngestApplicationService}）的关键区别：
 * <ul>
 *   <li>不建 {@code processing_progress}/{@code processing_claim}（outbound 不跑判定流水线）。</li>
 *   <li>{@code fingerprint}/{@code category}/{@code spam_score}/{@code dkim_passed} 恒 null
 *       （满足 {@code ck_message_outbound_no_judgment}）。</li>
 *   <li>自己生成 RFC Message-ID（写入邮件头），作为入库去重键。</li>
 *   <li>{@code message_source} 记 {@code (smtp, canonical=true)}。</li>
 * </ul>
 *
 * <p>仍复用：{@code getOrCreateThreadNode}、{@code message_mention} 写入、
 * {@link ThreadMergeApplicationService#mergeNewMessage} JWZ 归并、
 * {@link MailIndexService#refreshMessage} afterCommit Lucene 索引。
 */
@Service
public class OutboundMessageIngester {

    private static final Logger log = LoggerFactory.getLogger(OutboundMessageIngester.class);
    private static final short SETTINGS_ROW_ID = 1;

    private final ThreadNodeMapper threadNodeMapper;
    private final MessageMapper messageMapper;
    private final MessageSourceMapper messageSourceMapper;
    private final MessageMentionMapper messageMentionMapper;
    private final MailAccountMapper mailAccountMapper;
    private final AppSettingMapper appSettingMapper;
    private final ThreadMergeApplicationService threadMergeApplicationService;
    private final MailIndexService mailIndexService;

    public OutboundMessageIngester(
            ThreadNodeMapper threadNodeMapper,
            MessageMapper messageMapper,
            MessageSourceMapper messageSourceMapper,
            MessageMentionMapper messageMentionMapper,
            MailAccountMapper mailAccountMapper,
            AppSettingMapper appSettingMapper,
            ThreadMergeApplicationService threadMergeApplicationService,
            MailIndexService mailIndexService) {
        this.threadNodeMapper = threadNodeMapper;
        this.messageMapper = messageMapper;
        this.messageSourceMapper = messageSourceMapper;
        this.messageMentionMapper = messageMentionMapper;
        this.mailAccountMapper = mailAccountMapper;
        this.appSettingMapper = appSettingMapper;
        this.threadMergeApplicationService = threadMergeApplicationService;
        this.mailIndexService = mailIndexService;
    }

    /**
     * 解析回复头：从原邮件（DB id）读 Message-ID 与引用链，构造 In-Reply-To 与 References。
     *
     * <p>发信前调用，结果传给 {@code SmtpMailSender} 构造 MIME 头。
     * {@code referenceChain} 在入库时写入 {@code message_mention}，也传给 JWZ 归并。
     *
     * @param inReplyToMessageId 原邮件的 DB id，null 表示新建邮件
     * @return 回复头信息，null 表示非回复邮件
     */
    public ReplyHeaders resolveReplyHeaders(Long inReplyToMessageId) {
        if (inReplyToMessageId == null) {
            return null;
        }
        Message original = messageMapper.selectOne(
                Wrappers.lambdaQuery(Message.class)
                        .eq(Message::getId, inReplyToMessageId)
                        .isNull(Message::getDeletedAt));
        if (original == null) {
            throw new ApiException(ApiError.MESSAGE_NOT_FOUND);
        }
        String originalMessageId = original.getMessageId();
        List<String> originalReferences = loadReferences(inReplyToMessageId);

        // References = 原邮件引用链 + 原邮件自身 Message-ID，去重保序。
        LinkedHashSet<String> referenceChain = new LinkedHashSet<>(originalReferences);
        referenceChain.add(originalMessageId);
        List<String> chain = List.copyOf(referenceChain);

        String referencesHeader = String.join(" ", chain);
        return new ReplyHeaders(originalMessageId, referencesHeader, chain);
    }

    /**
     * 发信成功后入库 outbound 邮件 + source + mentions + JWZ 归并 + Lucene afterCommit。
     *
     * @return 入库的 message id；null 表示已存在（FirstIngestWins，只补 source）
     */
    @Transactional
    public Long ingest(OutboundCommand command) {
        MailAccount account = mailAccountMapper.selectById(command.mailAccountId());
        if (account == null) {
            throw new ApiException(ApiError.MAIL_ACCOUNT_NOT_FOUND);
        }
        AppSetting setting = appSettingMapper.selectById(SETTINGS_ROW_ID);
        if (setting == null) {
            throw new IllegalStateException("应用配置行不存在");
        }

        List<String> references = command.referenceChain() != null
                ? command.referenceChain() : List.of();

        // 为自己的 Message-ID 建节点；为引用链中的每个 Message-ID 建节点。
        long ownNodeId = getOrCreateThreadNode(command.rfcMessageId());
        for (String reference : references) {
            getOrCreateThreadNode(reference);
        }

        Message message = toOutboundMessage(command, account, ownNodeId);
        Long insertedMessageId = messageMapper.insertOutbound(message);
        if (insertedMessageId == null) {
            // 已存在（服务商 Sent 副本先被 IMAP 同步进来）。只补 source + Lucene。
            Long existingId = messageMapper.selectExistingIdByDedupeKey(
                    command.mailAccountId(), command.rfcMessageId(), null);
            if (existingId == null) {
                throw new IllegalStateException("outbound 唯一约束命中后无法定位现有行");
            }
            insertSmtpSource(existingId, command.sentAt(), false);
            registerIndexRefresh(existingId);
            return existingId;
        }

        message.setId(insertedMessageId);
        insertSmtpSource(insertedMessageId, command.sentAt(), true);
        insertMentions(insertedMessageId, references);

        ThreadMergeApplicationService.MergeResult mergeResult =
                threadMergeApplicationService.mergeNewMessage(
                        message, references, setting.getThreadSizeLimit());
        registerIndexRefresh(insertedMessageId);
        return insertedMessageId;
    }

    /**
     * 软删除邮件（local_delete 提案执行）。对邮箱服务器只读，不删服务器邮件/标记。
     *
     * @return 实际删除的行数
     */
    @Transactional
    public int softDeleteMessages(List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return 0;
        }
        int updated = messageMapper.update(
                null,
                Wrappers.lambdaUpdate(Message.class)
                        .in(Message::getId, messageIds)
                        .isNull(Message::getDeletedAt)
                        .set(Message::getDeletedAt, OffsetDateTime.now()));
        if (updated > 0) {
            // afterCommit: 从 Lucene 索引删除已软删邮件。
            List<Long> deletedIds = messageIds;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        mailIndexService.deleteMessages(deletedIds);
                    } catch (SearchIndexUnavailableException exception) {
                        log.error("软删除后索引清理失败: messageIds={}", deletedIds, exception);
                    }
                }
            });
        }
        return updated;
    }

    private long getOrCreateThreadNode(String rfcMessageId) {
        Long insertedId = threadNodeMapper.insertIfAbsentReturningId(rfcMessageId);
        if (insertedId != null) {
            return insertedId;
        }
        Long existingId = threadNodeMapper.selectIdByRfcMessageId(rfcMessageId);
        if (existingId == null) {
            throw new IllegalStateException("ThreadNode 唯一冲突后无法重读");
        }
        return existingId;
    }

    private void insertSmtpSource(long messageId, OffsetDateTime receivedAt, boolean canonical) {
        MessageSource source = new MessageSource();
        source.setMessageIdPk(messageId);
        source.setChannelType(SourceChannelType.SMTP);
        source.setIsCanonical(canonical);
        source.setReceivedAt(receivedAt);
        int inserted = messageSourceMapper.insertIfAbsent(source);
        if (canonical && inserted != 1) {
            throw new IllegalStateException("outbound 邮件未能创建唯一 canonical source");
        }
    }

    private void insertMentions(long messageId, List<String> references) {
        for (int position = 0; position < references.size(); position++) {
            MessageMention mention = new MessageMention();
            mention.setMessageIdPk(messageId);
            mention.setReferencedRfcMessageId(references.get(position));
            mention.setPosition(position);
            messageMentionMapper.insertIfAbsent(mention);
        }
    }

    private List<String> loadReferences(long messageId) {
        List<MessageMention> mentions = messageMentionMapper.selectList(
                Wrappers.lambdaQuery(MessageMention.class)
                        .eq(MessageMention::getMessageIdPk, messageId)
                        .orderByAsc(MessageMention::getPosition));
        List<String> result = new ArrayList<>();
        for (MessageMention mention : mentions) {
            result.add(mention.getReferencedRfcMessageId());
        }
        return result;
    }

    private void registerIndexRefresh(long messageId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    mailIndexService.refreshMessage(messageId);
                } catch (SearchIndexUnavailableException exception) {
                    // PostgreSQL 是唯一事实源；索引失败不回滚已完成的发信入库事务。
                    log.error("outbound 邮件提交后索引写入失败: messageId={}", messageId, exception);
                }
            }
        });
    }

    private static Message toOutboundMessage(OutboundCommand command, MailAccount account, long ownNodeId) {
        Message message = new Message();
        message.setMailAccountId(command.mailAccountId());
        message.setMessageId(command.rfcMessageId());
        message.setThreadNodeId(ownNodeId);
        message.setFromDisplay(command.fromDisplayName());
        message.setFromAddress(command.fromAddress());
        message.setFromAddressDomain(extractDomain(command.fromAddress()));
        message.setRecipients(command.recipients());
        message.setSubject(command.subject());
        message.setBaseSubject(BaseSubject.extract(command.subject()));
        message.setReceivedAt(command.sentAt());
        message.setSentAt(command.sentAt());
        message.setBodyText(command.bodyText());
        return message;
    }

    private static String extractDomain(String emailAddress) {
        int atIndex = emailAddress.lastIndexOf('@');
        if (atIndex < 0 || atIndex == emailAddress.length() - 1) {
            return emailAddress.toLowerCase(Locale.ROOT);
        }
        return emailAddress.substring(atIndex + 1).toLowerCase(Locale.ROOT);
    }

    /** 回复头解析结果。 */
    public record ReplyHeaders(
            String inReplyTo,
            String references,
            List<String> referenceChain) {
    }

    /** outbound 入库命令。 */
    public record OutboundCommand(
            long mailAccountId,
            String rfcMessageId,
            String fromAddress,
            String fromDisplayName,
            Recipients recipients,
            String subject,
            String bodyText,
            OffsetDateTime sentAt,
            List<String> referenceChain) {
    }
}
