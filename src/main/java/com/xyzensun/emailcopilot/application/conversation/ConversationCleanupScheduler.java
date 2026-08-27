package com.xyzensun.emailcopilot.application.conversation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xyzensun.emailcopilot.domain.enums.ApprovalStatus;
import com.xyzensun.emailcopilot.domain.enums.TurnStatus;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.AppSetting;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.PendingAction;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Turn;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.AppSettingMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.PendingActionMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.TurnMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 对话侧的两个固定周期清扫（design.md §2.3）。
 *
 * <p><b>僵尸 running turn</b>：进程在工具循环中退出，留下 {@code running} 记录。
 * 清扫 {@code started_at < now() - turn_timeout_seconds} 的 running turn → {@code failed}，
 * 原因记 {@code PROCESS_INTERRUPTED}。用户重试创建新 Turn，不恢复隐藏调用栈。
 *
 * <p><b>过期 PendingAction</b>：清扫 {@code approval_status='pending' AND expires_at <= now()}
 * → {@code expired}。列表查询额外加 {@code expires_at > now()}，避免清扫窗口间把已过期当未决项显示。
 *
 * <p>固定周期为代码常量（不进 app_setting 或 feature flag），与阶段 6 的 MailIndexLifecycle 同属调度层。
 */
@Component
public class ConversationCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConversationCleanupScheduler.class);
    private static final short SETTINGS_ROW_ID = 1;
    private static final int CLEANUP_BATCH_SIZE = 100;

    private final TurnMapper turnMapper;
    private final PendingActionMapper pendingActionMapper;
    private final AppSettingMapper appSettingMapper;
    private final Clock clock;

    public ConversationCleanupScheduler(
            TurnMapper turnMapper,
            PendingActionMapper pendingActionMapper,
            AppSettingMapper appSettingMapper,
            Clock clock) {
        this.turnMapper = turnMapper;
        this.pendingActionMapper = pendingActionMapper;
        this.appSettingMapper = appSettingMapper;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void cleanupZombieTurns() {
        int turnTimeoutSeconds = getTurnTimeoutSeconds();
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minusSeconds(turnTimeoutSeconds);

        List<Turn> zombieTurns = turnMapper.selectList(
                Wrappers.lambdaQuery(Turn.class)
                        .eq(Turn::getStatus, TurnStatus.RUNNING)
                        .lt(Turn::getStartedAt, cutoff)
                        .last("limit " + CLEANUP_BATCH_SIZE));

        for (Turn turn : zombieTurns) {
            turnMapper.update(null,
                    Wrappers.lambdaUpdate(Turn.class)
                            .eq(Turn::getId, turn.getId())
                            .eq(Turn::getStatus, TurnStatus.RUNNING)
                            .set(Turn::getStatus, TurnStatus.FAILED)
                            .set(Turn::getFailureReason, "PROCESS_INTERRUPTED")
                            .set(Turn::getFinishedAt, OffsetDateTime.now(clock)));
        }
        if (!zombieTurns.isEmpty()) {
            log.warn("清扫 {} 个僵尸 running turn", zombieTurns.size());
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    @Transactional
    public void cleanupExpiredPendingActions() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<PendingAction> expired = pendingActionMapper.selectList(
                Wrappers.lambdaQuery(PendingAction.class)
                        .eq(PendingAction::getApprovalStatus, ApprovalStatus.PENDING)
                        .le(PendingAction::getExpiresAt, now)
                        .last("limit " + CLEANUP_BATCH_SIZE));

        for (PendingAction action : expired) {
            pendingActionMapper.update(null,
                    Wrappers.lambdaUpdate(PendingAction.class)
                            .eq(PendingAction::getId, action.getId())
                            .eq(PendingAction::getApprovalStatus, ApprovalStatus.PENDING)
                            .set(PendingAction::getApprovalStatus, ApprovalStatus.EXPIRED)
                            .set(PendingAction::getDecidedAt, now));
        }
        if (!expired.isEmpty()) {
            log.info("清扫 {} 个过期 PendingAction", expired.size());
        }
    }

    private int getTurnTimeoutSeconds() {
        AppSetting settings = appSettingMapper.selectById(SETTINGS_ROW_ID);
        if (settings == null || settings.getTurnTimeoutSeconds() == null) {
            return 120;
        }
        return settings.getTurnTimeoutSeconds();
    }
}
