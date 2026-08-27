package com.xyzensun.emailcopilot.application.mail;

import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.AttachmentMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MessageMapper;
import com.xyzensun.emailcopilot.infrastructure.search.SearchIndexUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 过期 inbound 邮件自动清理（阶段15）。
 *
 * <p><b>真删整行</b>：{@code DELETE FROM message}，释放空间，不可逆。与阶段11
 * {@link MessageDeletionApplicationService} 的「软删留骨架防边界复活」语义不同、并存：
 * 本服务按时间清老邮件，阶段11 按用户手动删可见邮件留骨架。
 *
 * <p><b>只删 inbound</b>：草稿独立表不受影响；outbound（发件历史）保留。
 * {@code MessageDirection} 仅 INBOUND/OUTBOUND，删除按 {@code direction='inbound'} 过滤。
 *
 * <p><b>不复活依据</b>：IMAP 是 UID 水位增量，下次同步只拉水位之后的新 UID，
 * 不重拉已删老邮件（见 design「不复活依据」）。
 *
 * <p><b>索引</b>：删前查 ids → afterCommit {@link MailIndexService#deleteMessages} 清 Lucene；
 * 现有 6h 对账兜底任何漏删。删附件先于删 message，避免孤儿附件行。
 */
@Service
public class MessageRetentionApplicationService {

    private static final Logger log = LoggerFactory.getLogger(MessageRetentionApplicationService.class);

    /**
     * 分批真删的单批规模。PG 单语句绑定参数上限 65535，而 retention 是无界删除——
     * 首次清理（如 initialSyncDays=365 的繁忙邮箱）可能一次到期数万封，foreach 展开
     * 超限会让清理抛异常、lastCleanupAt 不更新、每 tick 重试永久失败。1000 远低于
     * 上限且批次足够大，兼顾往返效率。
     */
    private static final int DELETE_BATCH_SIZE = 1_000;

    private final MessageMapper messageMapper;
    private final AttachmentMapper attachmentMapper;
    private final MailIndexService mailIndexService;
    private final Clock clock;

    public MessageRetentionApplicationService(
            MessageMapper messageMapper,
            AttachmentMapper attachmentMapper,
            MailIndexService mailIndexService,
            Clock clock) {
        this.messageMapper = messageMapper;
        this.attachmentMapper = attachmentMapper;
        this.mailIndexService = mailIndexService;
        this.clock = clock;
    }

    /**
     * 删除保留期之前的全部 inbound 邮件（真删整行）。
     *
     * @param retentionDays 保留天数；调用方已保证 ≥1（AppSettingService 校验 + DB check 约束）
     */
    @Transactional
    public void deleteExpiredInbound(int retentionDays) {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(retentionDays, ChronoUnit.DAYS);
        List<Long> ids = messageMapper.selectInboundIdsOlderThan(cutoff);
        if (ids.isEmpty()) {
            return;
        }
        // 分批删附件 + message：foreach 删除按 id 展开绑定参数，单批超 PG 65535 上限会
        // 整语句失败（见 DELETE_BATCH_SIZE 说明）。先删附件再删 message，同序与阶段11 一致，
        // 避免删 message 后留下孤儿附件行。
        int deleted = 0;
        for (int i = 0; i < ids.size(); i += DELETE_BATCH_SIZE) {
            List<Long> chunk = ids.subList(i, Math.min(i + DELETE_BATCH_SIZE, ids.size()));
            attachmentMapper.deleteByMessageIds(chunk);
            deleted += messageMapper.deleteByIdList(chunk);
        }
        scheduleIndexDeletion(ids);
        // 真删不可逆：落 INFO 日志可追溯（deleted 数 + cutoff 精确描述被删窗口）
        log.info("自动清理过期 inbound 邮件: deleted={} cutoff={} retentionDays={}",
                deleted, cutoff, retentionDays);
    }

    /**
     * 提交后从 Lucene 索引删除已真删邮件（与 MessageDeletionApplicationService 同模式）。
     * 索引触发方式不动：现有事件驱动增量 + 30s提交/5min补漏/6h对账，6h 对账兜底任何漏删。
     */
    private void scheduleIndexDeletion(List<Long> messageIds) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    mailIndexService.deleteMessages(messageIds);
                } catch (SearchIndexUnavailableException exception) {
                    log.error("自动清理后索引清理失败: messageIds={}", messageIds, exception);
                }
            }
        });
    }
}
