package com.xyzensun.emailcopilot.application.mail;

import com.xyzensun.emailcopilot.application.mail.model.BatchDeleteResult;
import com.xyzensun.emailcopilot.infrastructure.search.SearchIndexUnavailableException;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Message;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.AttachmentMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MessageMapper;
import com.xyzensun.emailcopilot.interfaces.error.ApiError;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 邮件删除（阶段11 方案 C）。
 *
 * <p><b>删除即同步清正文</b>：一次 UPDATE 同时写 deleted_at + 清空 body_text/translated_body/
 * summary + 标 purged，库内不产生「deleted_at 有值但 purged=false」中间态。原 DataPurge 的
 * 清正文逻辑并进删除动作，DataPurge 端点作废。
 *
 * <p><b>骨架保留防复活</b>：message 行不物理删，message_id/thread_node_id/deleted_at 留着占
 * uk 位，防 IMAP 重同步复活（DATABASE.md §3.3）。
 *
 * <p><b>对服务器只读</b>：删的是本地副本，服务器原件仍在。
 *
 * <p><b>并发</b>：不阻止 in_progress 邮件删除——删除写 deleted_at 后，流水线 worker 写回有
 * deleted_at IS NULL fencing（阶段5），写不回即自然取消，不脏写（design §9）。
 *
 * <p>三种触发都走本服务：UI 单封 / UI 批量 / local_delete 审批执行，保证删除行为一致。
 */
@Service
public class MessageDeletionApplicationService {

    private static final Logger log = LoggerFactory.getLogger(MessageDeletionApplicationService.class);

    private final MessageMapper messageMapper;
    private final AttachmentMapper attachmentMapper;
    private final MailIndexService mailIndexService;

    public MessageDeletionApplicationService(
            MessageMapper messageMapper,
            AttachmentMapper attachmentMapper,
            MailIndexService mailIndexService) {
        this.messageMapper = messageMapper;
        this.attachmentMapper = attachmentMapper;
        this.mailIndexService = mailIndexService;
    }

    /**
     * 单封删除。精确冲突：已删 → 409 MESSAGE_ALREADY_DELETED；不存在 → 404 MESSAGE_NOT_FOUND。
     */
    @Transactional
    public void deleteMessage(long id) {
        // 先删附件（同事务，顺序：附件先于 message update，避免附件残留）
        attachmentMapper.deleteByMessageIds(List.of(id));
        int updated = messageMapper.deleteAndPurgeInOneGo(List.of(id));
        if (updated > 0) {
            scheduleIndexDeletion(List.of(id));
            return;
        }
        // 命中 0：要么不存在，要么已删——补一次 SELECT 精确区分
        Message existing = messageMapper.selectById(id);
        if (existing == null) {
            throw new ApiException(ApiError.MESSAGE_NOT_FOUND);
        }
        if (existing.getDeletedAt() != null) {
            throw new ApiException(ApiError.MESSAGE_ALREADY_DELETED);
        }
        // 理论上不达：deleted_at 为 null 就会被 update 命中
    }

    /**
     * 批量删除，宽松语义：能删的都删，已删/不存在不让整批失败，返回三类计数。
     * 对齐 openapi BatchDeleteResult {deleted, alreadyDeleted, notFound}。
     */
    @Transactional
    public BatchDeleteResult batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new BatchDeleteResult(0, 0, 0);
        }
        // SELECT 一次性分桶（避免逐条查询）
        List<Message> existing = messageMapper.selectBatchIds(ids);
        List<Long> deletable = new ArrayList<>();
        int alreadyDeleted = 0;
        for (Message m : existing) {
            if (m.getDeletedAt() == null) {
                deletable.add(m.getId());
            } else {
                alreadyDeleted++;
            }
        }
        int notFound = ids.size() - existing.size();

        int deleted = 0;
        if (!deletable.isEmpty()) {
            attachmentMapper.deleteByMessageIds(deletable);
            deleted = messageMapper.deleteAndPurgeInOneGo(deletable);
            scheduleIndexDeletion(deletable);
        }
        return new BatchDeleteResult(deleted, alreadyDeleted, notFound);
    }

    /**
     * 提交后从 Lucene 索引删除已删邮件（与原 softDeleteMessages 同模式）。
     * 清正文不影响索引（索引存元数据 + 摘要，删行即从索引移除）。
     */
    private void scheduleIndexDeletion(List<Long> messageIds) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    mailIndexService.deleteMessages(messageIds);
                } catch (SearchIndexUnavailableException exception) {
                    log.error("删除后索引清理失败: messageIds={}", messageIds, exception);
                }
            }
        });
    }
}
