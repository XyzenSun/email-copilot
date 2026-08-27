package com.xyzensun.emailcopilot.application.mail;

import com.xyzensun.emailcopilot.application.settings.AppSettingService;
import com.xyzensun.emailcopilot.application.settings.GuardrailSettings;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MailAccount;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MailAccountMapper;
import com.xyzensun.emailcopilot.infrastructure.settings.MaintenanceTaskRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时 IMAP 同步（阶段15）。
 *
 * <p><b>tick + interval 热改</b>：固定 10s 短 tick，每 tick 读 {@link GuardrailSettings}
 * 判断到点，而非 {@code fixedDelayString}——这样在设置页改 {@code imapSyncIntervalSeconds}
 * 后下次 tick（≤10s）即生效，无需重启。
 *
 * <p><b>互斥</b>：复用 {@link MaintenanceTaskRegistry#tryStartSync} 账号级排他锁，
 * 手动同步与定时同步对同一账号天然互斥，不会并发；不同账号锁独立可并行。
 *
 * <p><b>lastSyncAt 在内存</b>：每账号记上次完成时刻，重启丢失最多多跑一次。
 * 放在 work 的 finally 内更新（执行线程）而非提交处：以实际完成时刻计间隔，
 * 且 tryStartSync 返回 empty（已被手动/上次占用）时不进入 work，不会错误推进未真正跑的账号；
 * 无论成功失败都更新，避免失败账号每 tick 重试打爆 IMAP。
 *
 * <p>入库走现有 {@link ImapSyncApplicationService}，新邮件由 processing 1s 轮询自动消费，
 * 本调度器只负责提交同步、不触发流水线。
 */
@Component
public class ImapSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(ImapSyncScheduler.class);

    private final MailAccountMapper mailAccountMapper;
    private final MaintenanceTaskRegistry taskRegistry;
    private final ImapSyncApplicationService imapSyncApplicationService;
    private final AppSettingService appSettingService;
    private final Clock clock;

    /** 账号 id → 上次同步完成时刻。内存态，重启丢失。 */
    private final Map<Long, Instant> lastSyncAt = new ConcurrentHashMap<>();

    public ImapSyncScheduler(
            MailAccountMapper mailAccountMapper,
            MaintenanceTaskRegistry taskRegistry,
            ImapSyncApplicationService imapSyncApplicationService,
            AppSettingService appSettingService,
            Clock clock) {
        this.mailAccountMapper = mailAccountMapper;
        this.taskRegistry = taskRegistry;
        this.imapSyncApplicationService = imapSyncApplicationService;
        this.appSettingService = appSettingService;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 30_000)
    public void tick() {
        GuardrailSettings settings = appSettingService.getGuardrails();
        if (!settings.autoSyncEnabled()) {
            return;
        }
        int intervalSeconds = settings.imapSyncIntervalSeconds();
        Instant now = clock.instant();
        List<MailAccount> accounts = mailAccountMapper.selectImapEnabledAccounts();
        for (MailAccount account : accounts) {
            long accountId = account.getId();
            if (!isDue(accountId, intervalSeconds, now)) {
                continue;
            }
            // 复用账号级排他锁：返回 empty = 该账号已有同步在跑（手动或上次定时），跳过不报错。
            Optional<String> taskId = taskRegistry.tryStartSync(accountId, "定时同步", reporter -> {
                try {
                    imapSyncApplicationService.synchronize(accountId, reporter);
                } finally {
                    // 无论成功失败都更新 lastSyncAt：避免失败账号每 tick 重试打爆 IMAP。
                    lastSyncAt.put(accountId, clock.instant());
                }
            });
            if (taskId.isEmpty() && log.isDebugEnabled()) {
                log.debug("定时同步跳过（已有同步在跑）: accountId={}", accountId);
            }
        }
    }

    /** 到点判定：从未同步过（lastSyncAt 缺失）或距上次完成已过 interval。 */
    private boolean isDue(Long accountId, int intervalSeconds, Instant now) {
        Instant last = lastSyncAt.get(accountId);
        return last == null || !last.isAfter(now.minusSeconds(intervalSeconds));
    }
}
