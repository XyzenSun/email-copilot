package com.xyzensun.emailcopilot.infrastructure.settings;

import com.xyzensun.emailcopilot.domain.enums.MaintenanceTaskStatus;
import com.xyzensun.emailcopilot.domain.enums.MaintenanceTaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 单实例内存维护任务注册表。
 *
 * <p>{@code account-delete} 占一把排他锁；同步按邮箱账号互斥，并与同一账号的物理删除互斥。
 * 任务不入库是有意取舍：应用重启时工作线程本身也已经中断，保留一个无法恢复的
 * {@code running} 状态只会误导用户。阶段11 起 DataPurge 排他任务已移除。
 */
@Component
public class MaintenanceTaskRegistry implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceTaskRegistry.class);
    private static final String GENERIC_FAILURE_MESSAGE = "任务执行失败，请查看服务端日志后重试";

    private final Object stateMonitor = new Object();
    private final Map<String, MutableTask> tasks = new HashMap<>();
    private final Map<Long, String> runningSyncTasks = new HashMap<>();
    private final Clock clock;
    private final ExecutorService executorService;

    private String runningExclusiveTaskId;
    private Long runningAccountDeleteMailAccountId;

    @Autowired
    public MaintenanceTaskRegistry(Clock clock) {
        this(clock, Executors.newVirtualThreadPerTaskExecutor());
    }

    MaintenanceTaskRegistry(Clock clock, ExecutorService executorService) {
        this.clock = clock;
        this.executorService = executorService;
    }

    /**
     * 账号删除占排他锁，且不能越过该账号仍在运行的同步。
     * 检查与登记共享同一 monitor，避免“双方都检查通过后同时启动”的窗口。
     */
    public Optional<String> tryStartAccountDelete(
            long mailAccountId, String initialProgress, MaintenanceTaskWork work) {
        MutableTask task;
        synchronized (stateMonitor) {
            if (runningExclusiveTaskId != null || runningSyncTasks.containsKey(mailAccountId)) {
                return Optional.empty();
            }
            task = register(MaintenanceTaskType.ACCOUNT_DELETE, initialProgress);
            runningExclusiveTaskId = task.id;
            runningAccountDeleteMailAccountId = mailAccountId;
        }
        submit(task, mailAccountId, work);
        return Optional.of(task.id);
    }

    /** 同一账号只允许一个同步任务，且不能与该账号的物理删除交错。 */
    public Optional<String> tryStartSync(long mailAccountId, String initialProgress, MaintenanceTaskWork work) {
        MutableTask task;
        synchronized (stateMonitor) {
            if (runningSyncTasks.containsKey(mailAccountId)
                    || Long.valueOf(mailAccountId).equals(runningAccountDeleteMailAccountId)) {
                return Optional.empty();
            }
            task = register(MaintenanceTaskType.SYNC, initialProgress);
            runningSyncTasks.put(mailAccountId, task.id);
        }
        submit(task, mailAccountId, work);
        return Optional.of(task.id);
    }

    public Optional<MaintenanceTaskSnapshot> get(String taskId) {
        synchronized (stateMonitor) {
            MutableTask task = tasks.get(taskId);
            return task == null ? Optional.empty() : Optional.of(task.snapshot());
        }
    }

    public boolean hasRunningExclusiveTask() {
        synchronized (stateMonitor) {
            return runningExclusiveTaskId != null;
        }
    }

    private MutableTask register(MaintenanceTaskType type, String initialProgress) {
        String taskId = type.getValue() + "-" + UUID.randomUUID();
        MutableTask task = new MutableTask(taskId, type, initialProgress, now());
        tasks.put(taskId, task);
        return task;
    }

    private void submit(MutableTask task, Long mailAccountId, MaintenanceTaskWork work) {
        try {
            executorService.submit(() -> execute(task, mailAccountId, work));
        } catch (RuntimeException ex) {
            synchronized (stateMonitor) {
                releaseLock(task, mailAccountId);
                tasks.remove(task.id);
            }
            throw ex;
        }
    }

    private void execute(MutableTask task, Long mailAccountId, MaintenanceTaskWork work) {
        MaintenanceTaskStatus terminalStatus;
        String safeError = null;
        try {
            work.run(progress -> updateProgress(task.id, progress));
            terminalStatus = MaintenanceTaskStatus.SUCCEEDED;
        } catch (ExpectedTaskFailure ex) {
            terminalStatus = MaintenanceTaskStatus.FAILED;
            safeError = ex.safeMessage();
        } catch (Exception ex) {
            // 异常原文只进服务端日志；API 的 error 使用固定安全文案，绝不夹带正文或凭据。
            log.error("维护任务执行失败: taskId={} type={}", task.id, task.type.getValue(), ex);
            terminalStatus = MaintenanceTaskStatus.FAILED;
            safeError = GENERIC_FAILURE_MESSAGE;
        }

        synchronized (stateMonitor) {
            task.status = terminalStatus;
            if (safeError != null) {
                task.progress = safeError;
                task.error = safeError;
            }
            task.finishedAt = now();
            // 状态进入终态与释放并发锁必须原子可见；否则轮询先看到 succeeded，立即重触发仍可能拿到 409。
            releaseLock(task, mailAccountId);
        }
    }

    private void updateProgress(String taskId, String progress) {
        synchronized (stateMonitor) {
            MutableTask task = tasks.get(taskId);
            if (task != null && task.status == MaintenanceTaskStatus.RUNNING) {
                task.progress = progress;
            }
        }
    }

    private void releaseLock(MutableTask task, Long mailAccountId) {
        if (task.type == MaintenanceTaskType.SYNC) {
            runningSyncTasks.remove(mailAccountId, task.id);
        } else if (task.id.equals(runningExclusiveTaskId)) {
            runningExclusiveTaskId = null;
            if (task.type == MaintenanceTaskType.ACCOUNT_DELETE) {
                runningAccountDeleteMailAccountId = null;
            }
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    @Override
    public void destroy() {
        executorService.shutdownNow();
    }

    @FunctionalInterface
    public interface MaintenanceTaskWork {
        void run(ProgressReporter progressReporter) throws Exception;
    }

    @FunctionalInterface
    public interface ProgressReporter {
        void update(String progress);
    }

    /**
     * 已知且可安全展示的任务失败，例如阶段 4 同步执行器尚未提供。
     * 不接受底层异常作为 message，避免外部服务器回显进入 API。
     */
    public static final class ExpectedTaskFailure extends Exception {

        private final String safeMessage;

        public ExpectedTaskFailure(String safeMessage) {
            super(null, null, false, false);
            this.safeMessage = safeMessage;
        }

        String safeMessage() {
            return safeMessage;
        }
    }

    private static final class MutableTask {

        private final String id;
        private final MaintenanceTaskType type;
        private final OffsetDateTime startedAt;

        private MaintenanceTaskStatus status = MaintenanceTaskStatus.RUNNING;
        private String progress;
        private OffsetDateTime finishedAt;
        private String error;

        private MutableTask(
                String id, MaintenanceTaskType type, String progress, OffsetDateTime startedAt) {
            this.id = id;
            this.type = type;
            this.progress = progress;
            this.startedAt = startedAt;
        }

        private MaintenanceTaskSnapshot snapshot() {
            return new MaintenanceTaskSnapshot(
                    id, type, status, progress, startedAt, finishedAt, error);
        }
    }
}
