package com.xyzensun.emailcopilot.application.mail;

import com.xyzensun.emailcopilot.application.settings.AppSettingService;
import com.xyzensun.emailcopilot.application.settings.GuardrailSettings;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 过期 inbound 邮件自动清理调度（阶段15）。
 *
 * <p><b>tick + 周期判断</b>：固定 1h 短 tick，每 tick 读 {@link GuardrailSettings} + 全局
 * {@code lastCleanupAt} 判断是否到周期（MVP 固定每天：距上次清理 ≥ 24h 或首次运行）。
 * 不用 {@code fixedDelay=24h} 是为了让 {@code autoDeleteEnabled} 开关和保留期热改即时生效。
 *
 * <p><b>真删整行</b>：到点调 {@link MessageRetentionApplicationService#deleteExpiredInbound}，
 * 该服务事务内删附件 → 删 message → afterCommit 清 Lucene。失败不更新 lastCleanupAt，
 * 下个 tick（≤1h）自然重试。
 *
 * <p>lastCleanupAt 是内存 volatile：重启丢失 → 首次 tick（5min 后）即触发一次清理，
 * 符合 design「首次运行」语义。删除前/后的 INFO 日志由 retention 服务落，可追溯。
 */
@Component
public class RetentionCleanupScheduler {

    /** MVP 固定每天一次；后续可加配置项。 */
    private static final long CLEANUP_PERIOD_HOURS = 24;

    private final MessageRetentionApplicationService messageRetentionApplicationService;
    private final AppSettingService appSettingService;
    private final Clock clock;

    /** 上次清理完成时刻；内存态，重启丢失。volatile：单调度线程写，避免可见性问题。 */
    private volatile Instant lastCleanupAt;

    public RetentionCleanupScheduler(
            MessageRetentionApplicationService messageRetentionApplicationService,
            AppSettingService appSettingService,
            Clock clock) {
        this.messageRetentionApplicationService = messageRetentionApplicationService;
        this.appSettingService = appSettingService;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
    public void tick() {
        GuardrailSettings settings = appSettingService.getGuardrails();
        if (!settings.autoDeleteEnabled()) {
            return;
        }
        Instant now = clock.instant();
        if (lastCleanupAt != null && lastCleanupAt.isAfter(now.minus(CLEANUP_PERIOD_HOURS, ChronoUnit.HOURS))) {
            // 未到周期（距上次清理不足 24h）：跳过
            return;
        }
        // 到点（首次运行或满 24h）：真删过期 inbound。失败会抛出 → lastCleanupAt 不更新 → 下个 tick 重试。
        messageRetentionApplicationService.deleteExpiredInbound(settings.messageRetentionDays());
        lastCleanupAt = clock.instant();
    }
}
