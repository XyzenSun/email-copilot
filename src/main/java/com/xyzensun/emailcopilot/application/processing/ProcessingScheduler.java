package com.xyzensun.emailcopilot.application.processing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 后台轮询入口；每轮设上限，避免积压队列长期占住 Spring 调度线程。 */
@Component
@ConditionalOnProperty(
        prefix = "email-copilot.processing",
        name = "scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class ProcessingScheduler {

    private static final int MAXIMUM_STAGES_PER_POLL = 32;

    private final ProcessingApplicationService processingService;
    private final String workerId = "processing-" + UUID.randomUUID();

    public ProcessingScheduler(ProcessingApplicationService processingService) {
        this.processingService = processingService;
    }

    @Scheduled(fixedDelayString = "${email-copilot.processing.poll-delay-ms:1000}")
    public void processPendingStages() {
        for (int processed = 0; processed < MAXIMUM_STAGES_PER_POLL; processed++) {
            if (!processingService.processNext(workerId)) {
                return;
            }
        }
    }
}
