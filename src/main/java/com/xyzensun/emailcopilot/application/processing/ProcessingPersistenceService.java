package com.xyzensun.emailcopilot.application.processing;

import com.xyzensun.emailcopilot.domain.enums.ProcessingStage;
import com.xyzensun.emailcopilot.domain.enums.ProcessingStatus;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Message;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.ProcessingProgress;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MessageMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.ProcessingClaimMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.ProcessingProgressMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * 阶段领取与写回的短事务边界。
 *
 * <p>远程 AI 调用只使用这里返回的快照，绝不发生在本服务事务内；结果写回再次同时校验
 * worker、fencing version、未过期租约和消息未删除/未 purge。
 */
@Service
public class ProcessingPersistenceService {

    private static final int MAXIMUM_CLAIM_RACE_RETRIES = 8;

    private final ProcessingClaimMapper claimMapper;
    private final ProcessingProgressMapper progressMapper;
    private final MessageMapper messageMapper;
    private final Clock clock;

    public ProcessingPersistenceService(
            ProcessingClaimMapper claimMapper,
            ProcessingProgressMapper progressMapper,
            MessageMapper messageMapper,
            Clock clock) {
        this.claimMapper = claimMapper;
        this.progressMapper = progressMapper;
        this.messageMapper = messageMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ProcessingLease> claimNext(String workerId, Duration leaseDuration) {
        validateWorkerAndDuration(workerId, leaseDuration);
        for (int attempt = 0; attempt < MAXIMUM_CLAIM_RACE_RETRIES; attempt++) {
            Long messageId = claimMapper.selectNextClaimableMessageId();
            if (messageId == null) {
                return Optional.empty();
            }
            OffsetDateTime leaseUntil = OffsetDateTime.now(clock).plus(leaseDuration);
            Integer version = claimMapper.claimMessage(messageId, workerId, leaseUntil);
            if (version != null) {
                return Optional.of(new ProcessingLease(messageId, workerId, version, leaseUntil));
            }
        }
        return Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ProcessingStageContext> start(ProcessingLease lease) {
        requireNonNull(lease, "处理租约不能为空");
        ProcessingProgress progress = progressMapper.selectById(lease.messageId());
        if (progress == null || progress.getStatus() != ProcessingStatus.PENDING) {
            claimMapper.releaseClaim(lease.messageId(), lease.workerId(), lease.version());
            return Optional.empty();
        }
        int started = progressMapper.startStage(
                lease.messageId(), progress.getStage(), lease.workerId(), lease.version());
        if (started != 1) {
            claimMapper.releaseClaim(lease.messageId(), lease.workerId(), lease.version());
            return Optional.empty();
        }
        Message message = messageMapper.selectById(lease.messageId());
        if (message == null || message.getDeletedAt() != null || Boolean.TRUE.equals(message.getPurged())) {
            throw new LostProcessingLeaseException();
        }
        return Optional.of(new ProcessingStageContext(lease, progress.getStage(), message));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ProcessingLease> renew(ProcessingLease lease, Duration leaseDuration) {
        requireNonNull(lease, "处理租约不能为空");
        validateDuration(leaseDuration);
        OffsetDateTime leaseUntil = OffsetDateTime.now(clock).plus(leaseDuration);
        int renewed = claimMapper.renewClaim(
                lease.messageId(), lease.workerId(), lease.version(), leaseUntil);
        return renewed == 1
                ? Optional.of(new ProcessingLease(
                        lease.messageId(), lease.workerId(), lease.version(), leaseUntil))
                : Optional.empty();
    }

    /**
     * 手动重新处理对指定邮件领取租约（阶段12）。与 {@link #claimNext} 不同：不靠队列选候选，
     * 直接对给定 messageId 做 CAS 领取，因此无 select-then-claim 竞态、不需重试循环。
     * claimMessage 守卫已含 claim 空闲 + 邮件未删未 purge（不查 progress.status，故可领
     * done/completed 的邮件）；返回 empty 仅表示被占用（自动 worker 持租约或另一次手动在跑）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ProcessingLease> claimSpecific(long messageId, String workerId, Duration leaseDuration) {
        if (messageId <= 0) {
            throw new IllegalArgumentException("messageId 必须为正数");
        }
        validateWorkerAndDuration(workerId, leaseDuration);
        OffsetDateTime leaseUntil = OffsetDateTime.now(clock).plus(leaseDuration);
        Integer version = claimMapper.claimMessage(messageId, workerId, leaseUntil);
        if (version == null) {
            return Optional.empty();
        }
        return Optional.of(new ProcessingLease(messageId, workerId, version, leaseUntil));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(
            ProcessingLease lease,
            ProcessingStage expectedStage,
            ProcessingStage nextStage,
            ProcessingStageResult result) {
        requireNonNull(lease, "处理租约不能为空");
        requireNonNull(expectedStage, "预期阶段不能为空");
        requireNonNull(nextStage, "下一阶段不能为空");
        requireNonNull(result, "阶段结果不能为空");

        if (result.writesMessage()) {
            int messageRows = messageMapper.applyProcessingStageResult(
                    lease.messageId(), lease.workerId(), lease.version(), result);
            requireSingleRow(messageRows);
        }
        int progressRows = progressMapper.advanceStage(
                lease.messageId(), expectedStage, nextStage, lease.workerId(), lease.version());
        requireSingleRow(progressRows);
        int releasedRows = claimMapper.releaseClaim(
                lease.messageId(), lease.workerId(), lease.version());
        requireSingleRow(releasedRows);
    }

    /**
     * 手动重新处理的写回（阶段12）：与 {@link #complete} 的区别是<b>不推进游标</b>
     * （不调 advanceStage），也不标记失败（不调 markFailure）。手动只刷新产物列，
     * {@code processing_progress} 的 stage/status/retry_count 全程不动（{@code design.md} §5/§5.4）。
     * none()（如中文邮件手动翻译）时跳过 message UPDATE，只释放租约。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeManualResult(ProcessingLease lease, ProcessingStageResult result) {
        requireNonNull(lease, "处理租约不能为空");
        requireNonNull(result, "阶段结果不能为空");

        if (result.writesMessage()) {
            int messageRows = messageMapper.applyProcessingStageResult(
                    lease.messageId(), lease.workerId(), lease.version(), result);
            requireSingleRow(messageRows);
        }
        int releasedRows = claimMapper.releaseClaim(
                lease.messageId(), lease.workerId(), lease.version());
        requireSingleRow(releasedRows);
    }

    /**
     * 手动重新处理失败时释放租约（阶段12）：不调 {@link #fail}（不污染 processing_progress），
     * 只释放 claim 让该封可被立即重新手动触发或自动 worker 领取。不 requireSingleRow：
     * 释放时 lease 可能恰好过期、已被自动 worker 重新领走，0 行是正常取消而非异常。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseLease(ProcessingLease lease) {
        requireNonNull(lease, "处理租约不能为空");
        claimMapper.releaseClaim(lease.messageId(), lease.workerId(), lease.version());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(
            ProcessingLease lease,
            ProcessingStage expectedStage,
            String stableErrorCode,
            int retryLimit) {
        requireNonNull(lease, "处理租约不能为空");
        requireNonNull(expectedStage, "预期阶段不能为空");
        if (stableErrorCode == null || stableErrorCode.isBlank()) {
            throw new IllegalArgumentException("稳定错误码不能为空");
        }
        if (retryLimit < 0) {
            throw new IllegalArgumentException("重试上限不能为负数");
        }

        int progressRows = progressMapper.markFailure(
                lease.messageId(), expectedStage, lease.workerId(), lease.version(),
                stableErrorCode, retryLimit);
        requireSingleRow(progressRows);
        int releasedRows = claimMapper.releaseClaim(
                lease.messageId(), lease.workerId(), lease.version());
        requireSingleRow(releasedRows);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverExpiredInProgress() {
        return progressMapper.recoverExpiredInProgress();
    }

    private static void requireSingleRow(int affectedRows) {
        if (affectedRows != 1) {
            throw new LostProcessingLeaseException();
        }
    }

    private static void validateWorkerAndDuration(String workerId, Duration duration) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId 不能为空");
        }
        validateDuration(duration);
    }

    private static void validateDuration(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("租约时长必须为正数");
        }
    }
}
