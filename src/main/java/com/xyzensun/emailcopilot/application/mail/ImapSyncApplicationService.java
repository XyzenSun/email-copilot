package com.xyzensun.emailcopilot.application.mail;

import com.xyzensun.emailcopilot.domain.enums.SecretType;
import com.xyzensun.emailcopilot.domain.mail.ImapUidSyncPlan;
import com.xyzensun.emailcopilot.domain.mail.ParsedInboundMessage;
import com.xyzensun.emailcopilot.infrastructure.mail.AngusImapMailboxClient;
import com.xyzensun.emailcopilot.infrastructure.mail.ImapAccessException;
import com.xyzensun.emailcopilot.infrastructure.mail.InboundMessageProcessor;
import com.xyzensun.emailcopilot.infrastructure.mail.MessageContentRejectedException;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.AppSetting;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.ImapFolderCursor;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MailAccount;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.AppSettingMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MailAccountMapper;
import com.xyzensun.emailcopilot.infrastructure.security.ExternalAccountSecretStore;
import com.xyzensun.emailcopilot.infrastructure.settings.MaintenanceTaskRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 单账号真实 IMAP 同步编排。远程 LIST/FETCH、MIME 和 DNS 全部在数据库事务外；
 * cursor 与单封入库分别调用短事务应用服务。
 */
@Service
public class ImapSyncApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ImapSyncApplicationService.class);
    private static final short SETTINGS_ROW_ID = 1;
    private static final Duration CURSOR_LEASE_DURATION = Duration.ofMinutes(5);
    private static final Duration CURSOR_RENEW_THRESHOLD = Duration.ofMinutes(1);

    private final MailAccountMapper mailAccountMapper;
    private final AppSettingMapper appSettingMapper;
    private final ExternalAccountSecretStore externalAccountSecretStore;
    private final AngusImapMailboxClient mailboxClient;
    private final ImapCursorApplicationService cursorService;
    private final InboundMessageProcessor inboundMessageProcessor;
    private final IngestApplicationService ingestApplicationService;
    private final Clock clock;

    public ImapSyncApplicationService(
            MailAccountMapper mailAccountMapper,
            AppSettingMapper appSettingMapper,
            ExternalAccountSecretStore externalAccountSecretStore,
            AngusImapMailboxClient mailboxClient,
            ImapCursorApplicationService cursorService,
            InboundMessageProcessor inboundMessageProcessor,
            IngestApplicationService ingestApplicationService,
            Clock clock) {
        this.mailAccountMapper = mailAccountMapper;
        this.appSettingMapper = appSettingMapper;
        this.externalAccountSecretStore = externalAccountSecretStore;
        this.mailboxClient = mailboxClient;
        this.cursorService = cursorService;
        this.inboundMessageProcessor = inboundMessageProcessor;
        this.ingestApplicationService = ingestApplicationService;
        this.clock = clock;
    }

    public void synchronize(
            long mailAccountId,
            MaintenanceTaskRegistry.ProgressReporter progressReporter)
            throws MaintenanceTaskRegistry.ExpectedTaskFailure {
        MailAccount account = mailAccountMapper.selectById(mailAccountId);
        if (account == null || !Boolean.TRUE.equals(account.getImapEnabled())) {
            progressReporter.update("邮箱账号已删除或 IMAP 已停用，同步已取消");
            return;
        }
        AppSetting settings = appSettingMapper.selectById(SETTINGS_ROW_ID);
        if (settings == null) {
            throw new MaintenanceTaskRegistry.ExpectedTaskFailure("应用配置缺失，无法开始 IMAP 同步");
        }
        String password = externalAccountSecretStore.load(SecretType.IMAP_PASSWORD, mailAccountId)
                .orElseThrow(() -> new MaintenanceTaskRegistry.ExpectedTaskFailure(
                        "IMAP 凭据未配置，未同步任何邮件"));
        String workerId = "imap-" + UUID.randomUUID();

        log.info("IMAP 同步开始: accountId={}", mailAccountId);
        try (AngusImapMailboxClient.Connection connection = mailboxClient.connect(account, password)) {
            List<String> mailboxes = connection.discoverActiveMailboxes(account.getImapFolders());
            progressReporter.update("已发现 " + mailboxes.size() + " 个接收 mailbox");
            int processedMailboxCount = 0;
            long terminalMessageCount = 0;
            for (String mailboxName : mailboxes) {
                progressReporter.update("正在只读同步 mailbox: " + mailboxName);
                MailboxSyncResult result = synchronizeMailbox(
                        account,
                        mailboxName,
                        connection,
                        settings.getInitialSyncDays(),
                        workerId,
                        progressReporter);
                if (result.accountDisabled()) {
                    progressReporter.update("IMAP 已停用，同步在写入前安全取消");
                    return;
                }
                processedMailboxCount++;
                terminalMessageCount += result.terminalMessageCount();
            }
            progressReporter.update(
                    "IMAP 同步完成：" + processedMailboxCount + " 个 mailbox，"
                            + terminalMessageCount + " 个 UID 达到终态");
            log.info("IMAP 同步完成: accountId={} mailboxes={} terminalUids={}",
                    mailAccountId, processedMailboxCount, terminalMessageCount);
        } catch (ImapAccessException ex) {
            log.warn("IMAP 同步协议失败: accountId={} errorCode={}",
                    mailAccountId, ex.errorCode());
            throw new MaintenanceTaskRegistry.ExpectedTaskFailure(ex.getMessage());
        }
    }

    private MailboxSyncResult synchronizeMailbox(
            MailAccount account,
            String mailboxName,
            AngusImapMailboxClient.Connection connection,
            int initialSyncDays,
            String workerId,
            MaintenanceTaskRegistry.ProgressReporter progressReporter)
            throws ImapAccessException, MaintenanceTaskRegistry.ExpectedTaskFailure {
        try (AngusImapMailboxClient.Mailbox mailbox = connection.openMailbox(mailboxName)) {
            AngusImapMailboxClient.MailboxSnapshot snapshot = mailbox.snapshot();
            ImapFolderCursor cursor = cursorService.getOrCreateCursor(
                    account.getId(), mailboxName, snapshot.uidValidity(), initialSyncDays);
            Optional<ImapFolderCursor> claimed = cursorService.claimCursor(
                    cursor.getId(), workerId, leaseUntil());
            if (claimed.isEmpty()) {
                throw new MaintenanceTaskRegistry.ExpectedTaskFailure(
                        "mailbox 正由另一执行者同步，请等待租约到期后重试: " + mailboxName);
            }
            CursorLease lease = new CursorLease(claimed.orElseThrow());
            try {
                ImapUidSyncPlan plan = createPlan(lease.cursor(), snapshot);
                if (plan.uidValidityResetRequired()) {
                    Optional<ImapFolderCursor> reset = cursorService.resetForUidValidityChange(
                            lease.cursor().getId(),
                            workerId,
                            lease.cursor().getVersion(),
                            snapshot.uidValidity());
                    if (reset.isEmpty()) {
                        throw lostLease(mailboxName);
                    }
                    lease.update(reset.orElseThrow());
                    log.warn("IMAP UIDVALIDITY 改变，重置 mailbox 水位: accountId={} folder={}",
                            account.getId(), mailboxName);
                    plan = createPlan(lease.cursor(), snapshot);
                }

                List<AngusImapMailboxClient.MessageHandle> messages = mailbox.listMessages(plan);
                long terminalCount = 0;
                for (AngusImapMailboxClient.MessageHandle handle : messages) {
                    renewLeaseIfNeeded(lease, workerId, mailboxName);
                    if (handle.internalDate() == null) {
                        log.warn("IMAP 消息确定性拒绝: accountId={} folder={} uid={} errorCode={}",
                                account.getId(), mailboxName, handle.uid(), "IMAP_INTERNALDATE_MISSING");
                        advance(lease, workerId, snapshot.uidValidity(), handle.uid(), mailboxName);
                        terminalCount++;
                        continue;
                    }

                    try (InputStream rawMessage = handle.openRawMimeStream()) {
                        ParsedInboundMessage parsed = inboundMessageProcessor.process(rawMessage);
                        // MIME spool 与 DKIM/DNS 可能耗时较长。业务入库前必须重新通过数据库 CAS
                        // 确认租约仍属于当前执行者，不能让过期 worker 写入规范邮件表。
                        renewLeaseForWrite(lease, workerId, mailboxName);
                        IngestApplicationService.IngestResult ingestResult =
                                ingestApplicationService.ingestImap(
                                        new IngestApplicationService.IngestCommand(
                                                account.getId(), handle.internalDate(), parsed));
                        if (ingestResult.status() == IngestApplicationService.Status.CANCELLED) {
                            return new MailboxSyncResult(terminalCount, true);
                        }
                    } catch (MessageContentRejectedException ex) {
                        // 内容边界是确定性终态；不记录 Subject/正文/filename，只记录稳定错误码。
                        log.warn("IMAP 消息确定性拒绝: accountId={} folder={} uid={} errorCode={}",
                                account.getId(), mailboxName, handle.uid(), ex.errorCode());
                    } catch (IOException ex) {
                        log.warn("IMAP 原始 MIME 读取失败: accountId={} folder={} uid={} errorCode={}",
                                account.getId(), mailboxName, handle.uid(), "IMAP_STREAM_INTERRUPTED");
                        throw new MaintenanceTaskRegistry.ExpectedTaskFailure(
                                "读取原始邮件时连接中断，当前 UID 未越过: " + handle.uid());
                    }
                    advance(lease, workerId, snapshot.uidValidity(), handle.uid(), mailboxName);
                    terminalCount++;
                    progressReporter.update(
                            "mailbox " + mailboxName + " 已处理至 UID " + handle.uid());
                }

                // Bootstrap 窗口以前的 UID、EXPUNGE 空洞及本轮无候选区间都是确定性可越过项。
                if (snapshot.snapshotUpperUid() > lease.cursor().getLastSeenUid()) {
                    advance(
                            lease,
                            workerId,
                            snapshot.uidValidity(),
                            snapshot.snapshotUpperUid(),
                            mailboxName);
                }
                return new MailboxSyncResult(terminalCount, false);
            } finally {
                cursorService.releaseClaim(
                        lease.cursor().getId(), workerId, lease.cursor().getVersion());
            }
        }
    }

    private ImapUidSyncPlan createPlan(
            ImapFolderCursor cursor,
            AngusImapMailboxClient.MailboxSnapshot snapshot) {
        return ImapUidSyncPlan.create(
                cursor.getUidValidity(),
                cursor.getLastSeenUid(),
                cursor.getInitialSyncSince(),
                snapshot.uidValidity(),
                snapshot.uidNext(),
                snapshot.snapshotUpperUid());
    }

    private void advance(
            CursorLease lease,
            String workerId,
            long uidValidity,
            long completedUid,
            String mailboxName)
            throws MaintenanceTaskRegistry.ExpectedTaskFailure {
        Optional<ImapFolderCursor> advanced = cursorService.advanceCursor(
                lease.cursor().getId(),
                workerId,
                uidValidity,
                lease.cursor().getVersion(),
                completedUid);
        if (advanced.isEmpty()) {
            throw lostLease(mailboxName);
        }
        lease.update(advanced.orElseThrow());
    }

    private void renewLeaseIfNeeded(CursorLease lease, String workerId, String mailboxName)
            throws MaintenanceTaskRegistry.ExpectedTaskFailure {
        OffsetDateTime now = now();
        if (lease.cursor().getClaimUntil().isAfter(now.plus(CURSOR_RENEW_THRESHOLD))) {
            return;
        }
        renewLease(lease, workerId, mailboxName);
    }

    /** 远程内容处理完成后无条件 CAS 续租，把所有业务写入挡在最新 fencing token 之后。 */
    private void renewLeaseForWrite(CursorLease lease, String workerId, String mailboxName)
            throws MaintenanceTaskRegistry.ExpectedTaskFailure {
        renewLease(lease, workerId, mailboxName);
    }

    private void renewLease(CursorLease lease, String workerId, String mailboxName)
            throws MaintenanceTaskRegistry.ExpectedTaskFailure {
        Optional<ImapFolderCursor> renewed = cursorService.renewClaim(
                lease.cursor().getId(),
                workerId,
                lease.cursor().getVersion(),
                leaseUntil());
        if (renewed.isEmpty()) {
            throw lostLease(mailboxName);
        }
        lease.update(renewed.orElseThrow());
    }

    private static MaintenanceTaskRegistry.ExpectedTaskFailure lostLease(String mailboxName) {
        return new MaintenanceTaskRegistry.ExpectedTaskFailure(
                "mailbox 同步租约已丢失，旧执行者已停止: " + mailboxName);
    }

    private OffsetDateTime leaseUntil() {
        return now().plus(CURSOR_LEASE_DURATION);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static final class CursorLease {

        private ImapFolderCursor cursor;

        private CursorLease(ImapFolderCursor cursor) {
            this.cursor = cursor;
        }

        private ImapFolderCursor cursor() {
            return cursor;
        }

        private void update(ImapFolderCursor cursor) {
            this.cursor = cursor;
        }
    }

    private record MailboxSyncResult(long terminalMessageCount, boolean accountDisabled) {
    }
}
