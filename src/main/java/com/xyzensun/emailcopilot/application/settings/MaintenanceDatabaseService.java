package com.xyzensun.emailcopilot.application.settings;

import com.xyzensun.emailcopilot.application.mail.MailIndexService;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MailAccount;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.ImapFolderCursorMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MailAccountMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MailAccountSettingsMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MaintenanceSettingsMapper;
import com.xyzensun.emailcopilot.infrastructure.search.SearchIndexUnavailableException;
import com.xyzensun.emailcopilot.interfaces.error.ApiError;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/** 账号物理删除的短本地事务。DataPurge 批次已作废（阶段11 删除时同步清正文）。 */
@Service
public class MaintenanceDatabaseService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceDatabaseService.class);

    private final MaintenanceSettingsMapper maintenanceSettingsMapper;
    private final MailAccountSettingsMapper mailAccountSettingsMapper;
    private final MailAccountMapper mailAccountMapper;
    private final ImapFolderCursorMapper imapFolderCursorMapper;
    private final MailIndexService mailIndexService;

    public MaintenanceDatabaseService(
            MaintenanceSettingsMapper maintenanceSettingsMapper,
            MailAccountSettingsMapper mailAccountSettingsMapper,
            MailAccountMapper mailAccountMapper,
            ImapFolderCursorMapper imapFolderCursorMapper,
            MailIndexService mailIndexService) {
        this.maintenanceSettingsMapper = maintenanceSettingsMapper;
        this.mailAccountSettingsMapper = mailAccountSettingsMapper;
        this.mailAccountMapper = mailAccountMapper;
        this.imapFolderCursorMapper = imapFolderCursorMapper;
        this.mailIndexService = mailIndexService;
    }

    /**
     * 物理删除账号拥有的数据，但保留共享 thread_node、turn_read_evidence 与审批历史。
     *
     * <p>先锁账号行并重新验证停用状态。这样 DELETE 请求受理后若另一个 PATCH 抢先重新启用，
     * 任务会在删除任何子行之前整体失败；反过来，删除已经持锁时 PATCH 会等待并最终发现账号不存在。
     */
    @Transactional
    public void deleteMailAccount(long mailAccountId) {
        Long lockedId = mailAccountSettingsMapper.lockById(mailAccountId);
        if (lockedId == null) {
            throw new ApiException(ApiError.MAIL_ACCOUNT_NOT_FOUND);
        }
        MailAccount account = mailAccountMapper.selectById(mailAccountId);
        if (!isDisabled(account)) {
            throw new ApiException(ApiError.MAIL_ACCOUNT_NOT_DISABLED);
        }

        // 历史提案和不可变内容快照必须保留；只把已经无法执行的 pending 状态转为 cancelled。
        maintenanceSettingsMapper.cancelPendingContentActions(mailAccountId);
        maintenanceSettingsMapper.cancelPendingReplyActions(mailAccountId);
        maintenanceSettingsMapper.cancelPendingLocalDeleteActions(mailAccountId);

        // cursor 是账号级控制状态，必须先于邮件和账号物理删除，不能留下孤儿租约。
        imapFolderCursorMapper.deleteByMailAccountId(mailAccountId);

        // 全库没有 FK，所有邮件子表都按逻辑引用显式清理，且必须在删 message 之前完成。
        maintenanceSettingsMapper.deleteMessageSources(mailAccountId);
        maintenanceSettingsMapper.deleteProcessingProgress(mailAccountId);
        maintenanceSettingsMapper.deleteProcessingClaims(mailAccountId);
        maintenanceSettingsMapper.deleteAttachments(mailAccountId);
        maintenanceSettingsMapper.deleteMessageMentions(mailAccountId);
        maintenanceSettingsMapper.deleteDrafts(mailAccountId);
        maintenanceSettingsMapper.deleteSecrets(mailAccountId);
        maintenanceSettingsMapper.deleteMessages(mailAccountId);

        if (maintenanceSettingsMapper.deleteDisabledAccount(mailAccountId) != 1) {
            throw new ApiException(ApiError.MAIL_ACCOUNT_NOT_DISABLED);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    mailIndexService.deleteMailAccount(mailAccountId);
                } catch (SearchIndexUnavailableException exception) {
                    // 账号数据已经提交删除；索引孤儿由后续差集补偿，不影响删除任务结果。
                    log.error("账号删除提交后索引清理失败: mailAccountId={}", mailAccountId, exception);
                }
            }
        });
    }

    private static boolean isDisabled(MailAccount account) {
        return !Boolean.TRUE.equals(account.getImapEnabled())
                && !Boolean.TRUE.equals(account.getSmtpEnabled());
    }
}
