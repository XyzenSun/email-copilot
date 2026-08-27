package com.xyzensun.emailcopilot.application.mail;

import com.xyzensun.emailcopilot.domain.AttachmentMeta;
import com.xyzensun.emailcopilot.domain.enums.MessageDirection;
import com.xyzensun.emailcopilot.domain.enums.ProcessingStage;
import com.xyzensun.emailcopilot.domain.enums.ProcessingStatus;
import com.xyzensun.emailcopilot.domain.enums.SourceChannelType;
import com.xyzensun.emailcopilot.domain.mail.ParsedInboundMessage;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.AppSetting;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Attachment;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MailAccount;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Message;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MessageMention;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MessageSource;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.ProcessingProgress;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.AppSettingMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.AttachmentMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MailAccountMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MailAccountSettingsMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MessageMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MessageMentionMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MessageSourceMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.ProcessingClaimMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.ProcessingProgressMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.ThreadNodeMapper;
import com.xyzensun.emailcopilot.infrastructure.search.SearchIndexUnavailableException;
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

/**
 * IMAP 邮件规范入库用例。
 *
 * <p>父账号栅栏、FirstIngestWins、canonical source、附件元数据、mentions/nodes、
 * {@code processing_progress} 和 JWZ 归并在同一个短数据库事务内完成。远程 IMAP、DNS、
 * MIME spool 均在调用本服务前完成；本服务从不做远程 I/O。
 */
@Service
public class IngestApplicationService {

    private static final Logger log = LoggerFactory.getLogger(IngestApplicationService.class);
    private static final short SETTINGS_ROW_ID = 1;
    private static final String LOCAL_SYNTHETIC_SUFFIX = "@email-copilot.local>";

    private final MailAccountSettingsMapper mailAccountSettingsMapper;
    private final MailAccountMapper mailAccountMapper;
    private final AppSettingMapper appSettingMapper;
    private final ThreadNodeMapper threadNodeMapper;
    private final MessageMapper messageMapper;
    private final MessageSourceMapper messageSourceMapper;
    private final MessageMentionMapper messageMentionMapper;
    private final AttachmentMapper attachmentMapper;
    private final ProcessingProgressMapper processingProgressMapper;
    private final ProcessingClaimMapper processingClaimMapper;
    private final ThreadMergeApplicationService threadMergeApplicationService;
    private final MailIndexService mailIndexService;

    public IngestApplicationService(
            MailAccountSettingsMapper mailAccountSettingsMapper,
            MailAccountMapper mailAccountMapper,
            AppSettingMapper appSettingMapper,
            ThreadNodeMapper threadNodeMapper,
            MessageMapper messageMapper,
            MessageSourceMapper messageSourceMapper,
            MessageMentionMapper messageMentionMapper,
            AttachmentMapper attachmentMapper,
            ProcessingProgressMapper processingProgressMapper,
            ProcessingClaimMapper processingClaimMapper,
            ThreadMergeApplicationService threadMergeApplicationService,
            MailIndexService mailIndexService) {
        this.mailAccountSettingsMapper = mailAccountSettingsMapper;
        this.mailAccountMapper = mailAccountMapper;
        this.appSettingMapper = appSettingMapper;
        this.threadNodeMapper = threadNodeMapper;
        this.messageMapper = messageMapper;
        this.messageSourceMapper = messageSourceMapper;
        this.messageMentionMapper = messageMentionMapper;
        this.attachmentMapper = attachmentMapper;
        this.processingProgressMapper = processingProgressMapper;
        this.processingClaimMapper = processingClaimMapper;
        this.threadMergeApplicationService = threadMergeApplicationService;
        this.mailIndexService = mailIndexService;
    }

    /**
     * 外部 I/O 后第一步重新锁父账号。账号已删除或 IMAP 已停用时返回正常取消，
     * 同步器可停止 mailbox，不能制造孤儿 message。
     */
    @Transactional
    public IngestResult ingestImap(IngestCommand command) {
        validateCommand(command);
        if (mailAccountSettingsMapper.lockById(command.mailAccountId()) == null) {
            return IngestResult.cancelled();
        }
        MailAccount account = mailAccountMapper.selectById(command.mailAccountId());
        if (account == null || !Boolean.TRUE.equals(account.getImapEnabled())) {
            return IngestResult.cancelled();
        }
        AppSetting setting = appSettingMapper.selectById(SETTINGS_ROW_ID);
        if (setting == null) {
            throw new IllegalStateException("应用配置行不存在");
        }

        ParsedInboundMessage parsed = command.message();
        List<String> references = safeGlobalReferences(parsed.references());
        long ownNodeId = getOrCreateThreadNode(parsed.messageId());
        for (String reference : references) {
            getOrCreateThreadNode(reference);
        }

        Message message = toMessage(command, ownNodeId);
        Long insertedMessageId = messageMapper.insertInboundIfAbsent(message);
        if (insertedMessageId == null) {
            Long existingMessageId = messageMapper.selectExistingIdByDedupeKey(
                    command.mailAccountId(), parsed.messageId(), parsed.fingerprint());
            if (existingMessageId == null) {
                throw new IllegalStateException("邮件唯一约束命中后无法定位现有行");
            }
            insertSource(existingMessageId, command.receivedAt(), false, false);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        mailIndexService.refreshMessage(existingMessageId);
                    } catch (SearchIndexUnavailableException exception) {
                        // 数据库事实已经提交；索引失败只能记录并交给重放/差集补偿，不能把成功入库伪装成失败。
                        log.error("重复邮件提交后索引修复失败: messageId={}", existingMessageId, exception);
                    }
                }
            });
            return IngestResult.duplicate(existingMessageId);
        }

        message.setId(insertedMessageId);
        insertSource(insertedMessageId, command.receivedAt(), true, true);
        insertMentions(insertedMessageId, references);
        insertAttachments(insertedMessageId, parsed.attachments());
        insertInitialProcessingState(insertedMessageId);

        ThreadMergeApplicationService.MergeResult mergeResult =
                threadMergeApplicationService.mergeNewMessage(
                        message, references, setting.getThreadSizeLimit());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    mailIndexService.refreshMessage(insertedMessageId);
                } catch (SearchIndexUnavailableException exception) {
                    // PostgreSQL 是唯一事实源；索引失败不回滚已经完成的收信事务。
                    log.error("新邮件提交后索引写入失败: messageId={}", insertedMessageId, exception);
                }
            }
        });
        return IngestResult.ingested(
                insertedMessageId, mergeResult.representativeThreadNodeId());
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

    private void insertSource(
            long messageId,
            OffsetDateTime receivedAt,
            boolean canonical,
            boolean requireInserted) {
        MessageSource source = new MessageSource();
        source.setMessageIdPk(messageId);
        source.setChannelType(SourceChannelType.IMAP);
        source.setIsCanonical(canonical);
        source.setReceivedAt(receivedAt);
        int inserted = messageSourceMapper.insertIfAbsent(source);
        if (requireInserted && inserted != 1) {
            throw new IllegalStateException("首次邮件未能创建唯一 canonical source");
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

    private void insertAttachments(long messageId, List<AttachmentMeta> metadata) {
        for (AttachmentMeta item : metadata) {
            if (item.sizeBytes() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("附件大小超出数据库可记录范围");
            }
            Attachment attachment = new Attachment();
            attachment.setMessageIdPk(messageId);
            attachment.setFilename(item.filename());
            attachment.setContentType(item.contentType());
            attachment.setSizeBytes(Math.toIntExact(item.sizeBytes()));
            attachmentMapper.insert(attachment);
        }
    }

    private void insertInitialProcessingState(long messageId) {
        ProcessingProgress progress = new ProcessingProgress();
        progress.setMessageIdPk(messageId);
        progress.setStage(ProcessingStage.SENDER_RULE);
        progress.setStatus(ProcessingStatus.PENDING);
        progress.setRetryCount(0);
        processingProgressMapper.insert(progress);
        if (processingClaimMapper.insertIfAbsent(messageId) != 1) {
            // 首次入库与控制状态同事务；缺少 claim 行会让邮件永远无法被 worker 领取。
            throw new IllegalStateException("首次邮件未能创建处理租约行");
        }
    }

    private static Message toMessage(IngestCommand command, long ownNodeId) {
        ParsedInboundMessage parsed = command.message();
        Message message = new Message();
        message.setMailAccountId(command.mailAccountId());
        message.setDirection(MessageDirection.INBOUND);
        message.setMessageId(parsed.messageId());
        message.setFingerprint(parsed.fingerprint());
        message.setThreadNodeId(ownNodeId);
        message.setFromDisplay(parsed.fromDisplay());
        message.setFromAddress(parsed.fromAddress());
        message.setFromAddressDomain(parsed.fromAddressDomain());
        message.setFromAuthenticatedDomain(parsed.fromAuthenticatedDomain());
        message.setRecipients(parsed.recipients());
        message.setSubject(parsed.subject());
        message.setBaseSubject(parsed.baseSubject());
        message.setReceivedAt(command.receivedAt());
        message.setSentAt(parsed.sentAt());
        message.setBodyText(parsed.bodyText());
        message.setDkimPassed(parsed.dkimPassed());
        return message;
    }

    /** 本地合成 ID 永远不能从攻击者提供的 References 注册为可引用全局节点。 */
    private static List<String> safeGlobalReferences(List<String> references) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String reference : references) {
            if (reference != null
                    && !reference.toLowerCase(java.util.Locale.ROOT)
                            .endsWith(LOCAL_SYNTHETIC_SUFFIX)) {
                result.add(reference);
            }
        }
        return List.copyOf(result);
    }

    private static void validateCommand(IngestCommand command) {
        if (command == null || command.message() == null) {
            throw new IllegalArgumentException("入库命令与解析结果不能为空");
        }
        if (command.mailAccountId() <= 0) {
            throw new IllegalArgumentException("mailAccountId 必须为正数");
        }
        if (command.receivedAt() == null) {
            // INTERNALDATE 缺失不能退回邮件自带 Date；同步器应将该 UID 记为确定性拒绝。
            throw new IllegalArgumentException("IMAP INTERNALDATE 不能为空");
        }
    }

    public record IngestCommand(
            long mailAccountId,
            OffsetDateTime receivedAt,
            ParsedInboundMessage message) {
    }

    public record IngestResult(
            Status status,
            Long messageId,
            Long threadNodeId) {

        public static IngestResult ingested(long messageId, long threadNodeId) {
            return new IngestResult(Status.INGESTED, messageId, threadNodeId);
        }

        public static IngestResult duplicate(long messageId) {
            return new IngestResult(Status.DUPLICATE, messageId, null);
        }

        public static IngestResult cancelled() {
            return new IngestResult(Status.CANCELLED, null, null);
        }
    }

    public enum Status {
        INGESTED,
        DUPLICATE,
        CANCELLED
    }
}
