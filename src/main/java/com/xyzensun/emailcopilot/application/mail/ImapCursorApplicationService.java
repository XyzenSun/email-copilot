package com.xyzensun.emailcopilot.application.mail;

import com.xyzensun.emailcopilot.domain.mail.ImapUidSyncPlan;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.ImapFolderCursor;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.ImapFolderCursorMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * IMAP mailbox cursor 的短事务边界。
 *
 * <p>本服务不做任何远程 I/O。调用模式是：短事务创建/领取 → 提交 → 事务外 IMAP
 * LIST/FETCH → 单封业务事务提交 → 另一个短事务推进水位。CAS 返回空表示租约或版本
 * 已丢失，旧执行者必须停止，不能继续写入。
 */
@Service
public class ImapCursorApplicationService {

    private final ImapFolderCursorMapper cursorMapper;

    public ImapCursorApplicationService(ImapFolderCursorMapper cursorMapper) {
        this.cursorMapper = cursorMapper;
    }

    /** 并发初始化依靠唯一约束 + ON CONFLICT，随后总是重读数据库事实源。 */
    @Transactional
    public ImapFolderCursor getOrCreateCursor(
            long mailAccountId,
            String folderName,
            long currentUidValidity,
            int initialSyncDays) {
        validateUidValidity(currentUidValidity);
        if (folderName == null || folderName.isBlank()) {
            throw new IllegalArgumentException("folderName 不能为空");
        }
        if (initialSyncDays < 1 || initialSyncDays > 365) {
            throw new IllegalArgumentException("initialSyncDays 必须在 1..365");
        }
        cursorMapper.insertIfAbsent(
                mailAccountId, folderName, currentUidValidity, initialSyncDays);
        ImapFolderCursor cursor = cursorMapper.selectByAccountAndFolder(mailAccountId, folderName);
        if (cursor == null) {
            throw new IllegalStateException("IMAP cursor 初始化后无法重读");
        }
        return cursor;
    }

    @Transactional
    public Optional<ImapFolderCursor> claimCursor(
            long cursorId, String workerId, OffsetDateTime leaseUntil) {
        validateWorkerAndLease(workerId, leaseUntil);
        if (cursorMapper.claimCursor(cursorId, workerId, leaseUntil) != 1) {
            return Optional.empty();
        }
        return Optional.of(requireCursor(cursorId));
    }

    @Transactional
    public Optional<ImapFolderCursor> renewClaim(
            long cursorId, String workerId, int expectedVersion, OffsetDateTime leaseUntil) {
        validateWorkerAndLease(workerId, leaseUntil);
        if (cursorMapper.renewClaim(
                cursorId, workerId, expectedVersion, leaseUntil) != 1) {
            return Optional.empty();
        }
        return Optional.of(requireCursor(cursorId));
    }

    @Transactional
    public Optional<ImapFolderCursor> resetForUidValidityChange(
            long cursorId,
            String workerId,
            int expectedVersion,
            long newUidValidity) {
        validateUidValidity(newUidValidity);
        if (cursorMapper.resetCursorForUidValidityChange(
                cursorId, workerId, expectedVersion, newUidValidity) != 1) {
            return Optional.empty();
        }
        return Optional.of(requireCursor(cursorId));
    }

    @Transactional
    public Optional<ImapFolderCursor> advanceCursor(
            long cursorId,
            String workerId,
            long expectedUidValidity,
            int expectedVersion,
            long completedUid) {
        validateUidValidity(expectedUidValidity);
        if (completedUid < 1 || completedUid > ImapUidSyncPlan.MAX_UID) {
            throw new IllegalArgumentException("completedUid 超出 IMAP UID 范围");
        }
        if (cursorMapper.advanceCursor(
                cursorId,
                workerId,
                expectedUidValidity,
                expectedVersion,
                completedUid) != 1) {
            return Optional.empty();
        }
        return Optional.of(requireCursor(cursorId));
    }

    @Transactional
    public boolean releaseClaim(long cursorId, String workerId, int expectedVersion) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId 不能为空");
        }
        return cursorMapper.releaseClaim(cursorId, workerId, expectedVersion) == 1;
    }

    @Transactional(readOnly = true)
    public ImapFolderCursor getCursor(long cursorId) {
        return requireCursor(cursorId);
    }

    private ImapFolderCursor requireCursor(long cursorId) {
        ImapFolderCursor cursor = cursorMapper.selectById(cursorId);
        if (cursor == null) {
            throw new IllegalStateException("IMAP cursor 不存在: " + cursorId);
        }
        return cursor;
    }

    private static void validateUidValidity(long uidValidity) {
        if (uidValidity < 1 || uidValidity > ImapUidSyncPlan.MAX_UID) {
            throw new IllegalArgumentException("uidValidity 超出 IMAP 范围");
        }
    }

    private static void validateWorkerAndLease(String workerId, OffsetDateTime leaseUntil) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId 不能为空");
        }
        if (leaseUntil == null) {
            throw new IllegalArgumentException("leaseUntil 不能为空");
        }
    }
}
