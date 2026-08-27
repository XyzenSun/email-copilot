package com.xyzensun.emailcopilot.application.mail;

import com.xyzensun.emailcopilot.infrastructure.search.SearchIndexUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Lucene 投影的启动检查、持久化提交与固定周期补偿入口。 */
@Component
public final class MailIndexLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MailIndexLifecycle.class);

    private final MailIndexService mailIndexService;
    private volatile boolean initialized;

    public MailIndexLifecycle(MailIndexService mailIndexService) {
        this.mailIndexService = mailIndexService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        ensureInitialized();
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void commitIndex() {
        if (!ensureInitialized()) {
            return;
        }
        try {
            mailIndexService.commit();
        } catch (SearchIndexUnavailableException exception) {
            initialized = false;
            log.error("Lucene 索引定期提交失败", exception);
        }
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void replayRecentMessages() {
        if (!ensureInitialized()) {
            return;
        }
        try {
            mailIndexService.replayRecentCreatedMessages();
        } catch (SearchIndexUnavailableException exception) {
            initialized = false;
            log.error("Lucene 最近入库窗口重放失败", exception);
        }
    }

    @Scheduled(fixedDelay = 21_600_000, initialDelay = 21_600_000)
    public void reconcileIndex() {
        if (!ensureInitialized()) {
            return;
        }
        try {
            mailIndexService.reconcile();
        } catch (SearchIndexUnavailableException exception) {
            initialized = false;
            log.error("Lucene 与 PostgreSQL 双向差集补偿失败", exception);
        }
    }

    private boolean ensureInitialized() {
        if (initialized) {
            return true;
        }
        try {
            mailIndexService.initialize();
            initialized = true;
            log.info("Lucene 邮件索引已初始化");
            return true;
        } catch (SearchIndexUnavailableException exception) {
            log.error("Lucene 邮件索引初始化失败，数据库与收信仍可继续", exception);
            return false;
        }
    }
}
