package com.xyzensun.emailcopilot.application.processing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xyzensun.emailcopilot.application.settings.AppSettingService;
import com.xyzensun.emailcopilot.application.settings.PipelineSettings;
import com.xyzensun.emailcopilot.domain.enums.ProcessingStage;
import com.xyzensun.emailcopilot.domain.pipeline.ClassificationMode;
import com.xyzensun.emailcopilot.domain.pipeline.LanguageDetection;
import com.xyzensun.emailcopilot.domain.pipeline.LanguageDetector;
import com.xyzensun.emailcopilot.domain.pipeline.ProcessingStageTransition;
import com.xyzensun.emailcopilot.domain.pipeline.SenderRuleEvaluator;
import com.xyzensun.emailcopilot.domain.pipeline.SenderRuleOutcome;
import com.xyzensun.emailcopilot.infrastructure.ai.AiRuntimeSettings;
import com.xyzensun.emailcopilot.infrastructure.ai.ChatModelHolder;
import com.xyzensun.emailcopilot.infrastructure.ai.ClassificationResult;
import com.xyzensun.emailcopilot.infrastructure.ai.InvalidStructuredOutputException;
import com.xyzensun.emailcopilot.infrastructure.ai.PipelineAiClient;
import com.xyzensun.emailcopilot.infrastructure.ai.SpamJudgmentResult;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.SenderRule;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Tag;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.SenderRuleMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.TagMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 入库后判定流水线的单阶段编排器。
 *
 * <p>一次 {@link #processNext(String)} 最多处理一封邮件的一个内部阶段。领取/开始/写回分别由
 * 短事务完成，AI I/O 始终发生在事务外；失败只改变处理进度，绝不回滚或删除已经入库的邮件。
 */
@Service
public class ProcessingApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ProcessingApplicationService.class);
    /**
     * 租约覆盖最大合法 AI HTTP 超时及一分钟写回余量；二者共用运行期基线，
     * 避免未来只调整 AI 超时上限后把正常慢响应变成必然的迟到写回。
     */
    private static final Duration PROCESSING_LEASE_DURATION = Duration.ofSeconds(
            AiRuntimeSettings.MAX_TIMEOUT_SECONDS + 60L);
    private static final String ERROR_AI_PROVIDER = "AI_PROVIDER_FAILURE";
    private static final String ERROR_AI_OUTPUT = "AI_STRUCTURED_OUTPUT_INVALID";
    private static final String ERROR_PIPELINE = "PIPELINE_STAGE_FAILURE";

    private final ProcessingPersistenceService persistenceService;
    private final AppSettingService appSettingService;
    private final ChatModelHolder chatModelHolder;
    private final PipelineAiClient aiClient;
    private final SenderRuleMapper senderRuleMapper;
    private final TagMapper tagMapper;

    private final ProcessingStageTransition transition = new ProcessingStageTransition();
    private final SenderRuleEvaluator senderRuleEvaluator = new SenderRuleEvaluator();
    private final LanguageDetector languageDetector = new LanguageDetector();

    public ProcessingApplicationService(
            ProcessingPersistenceService persistenceService,
            AppSettingService appSettingService,
            ChatModelHolder chatModelHolder,
            PipelineAiClient aiClient,
            SenderRuleMapper senderRuleMapper,
            TagMapper tagMapper) {
        this.persistenceService = persistenceService;
        this.appSettingService = appSettingService;
        this.chatModelHolder = chatModelHolder;
        this.aiClient = aiClient;
        this.senderRuleMapper = senderRuleMapper;
        this.tagMapper = tagMapper;
    }

    /**
     * @return true 表示领取到候选并完成、取消或记录了一次阶段尝试；false 表示当前队列为空
     */
    public boolean processNext(String workerId) {
        persistenceService.recoverExpiredInProgress();
        Optional<ProcessingLease> claimed = persistenceService.claimNext(
                workerId, PROCESSING_LEASE_DURATION);
        if (claimed.isEmpty()) {
            return false;
        }

        ProcessingLease lease = claimed.orElseThrow();
        Optional<ProcessingStageContext> started;
        try {
            started = persistenceService.start(lease);
        } catch (LostProcessingLeaseException exception) {
            // start CAS 与读取邮件快照之间若软删除/DataPurge 胜出，同样按正常取消处理。
            return true;
        }
        if (started.isEmpty()) {
            return true;
        }
        ProcessingStageContext context = started.orElseThrow();
        int retryLimit = 0;
        try {
            PipelineSettings pipelineSettings = appSettingService.getPipelineSettings();
            retryLimit = appSettingService.getGuardrails().processingRetryLimit();
            processStage(context, pipelineSettings);
        } catch (LostProcessingLeaseException exception) {
            // 另一个 worker、软删除或 DataPurge 已胜出；迟到结果按正常取消处理。
        } catch (InvalidStructuredOutputException exception) {
            failSafely(context, ERROR_AI_OUTPUT, retryLimit);
        } catch (RuntimeException exception) {
            String errorCode = isAiStage(context.stage()) ? ERROR_AI_PROVIDER : ERROR_PIPELINE;
            failSafely(context, errorCode, retryLimit);
        }
        return true;
    }

    private void processStage(ProcessingStageContext context, PipelineSettings settings) {
        switch (context.stage()) {
            case SENDER_RULE -> processSenderRule(context);
            case SPAM_JUDGMENT -> processSpamJudgment(context, settings);
            case CLASSIFICATION -> processClassification(context, settings);
            case LANGUAGE_DETECTION -> processLanguageDetection(context, settings);
            case TRANSLATION -> processTranslation(context, settings);
            case SUMMARY -> processSummary(context, settings);
            case DONE -> throw new IllegalStateException("completed 游标不应进入领取队列");
        }
    }

    private void processSenderRule(ProcessingStageContext context) {
        List<SenderRule> rules = senderRuleMapper.selectList(
                Wrappers.lambdaQuery(SenderRule.class)
                        .eq(SenderRule::getEnabled, true));
        SenderRuleOutcome outcome = senderRuleEvaluator.evaluate(
                context.message().getFromAuthenticatedDomain(), rules);
        ProcessingStageResult result = outcome == SenderRuleOutcome.BLOCK
                ? ProcessingStageResult.blockedAsSpam()
                : ProcessingStageResult.none();
        persistenceService.complete(
                context.lease(),
                context.stage(),
                transition.afterSenderRule(outcome),
                result);
    }

    private void processSpamJudgment(ProcessingStageContext context, PipelineSettings settings) {
        if (!settings.spamCheckEnabled()) {
            completeWithoutResult(context, ProcessingStage.CLASSIFICATION);
            return;
        }
        ChatModel model = chatModelHolder.current();
        if (model == null) {
            completeWithoutResult(context, ProcessingStage.CLASSIFICATION);
            return;
        }

        SpamJudgmentResult judgment = aiClient.judgeSpam(
                model, context.message(), settings.spamJudgmentPrompt());
        ProcessingLease renewedLease = renewAfterRemote(context.lease());
        boolean spam = judgment.spamScore().compareTo(settings.spamClassificationThreshold()) >= 0;
        persistenceService.complete(
                renewedLease,
                context.stage(),
                spam ? ProcessingStage.DONE : ProcessingStage.CLASSIFICATION,
                ProcessingStageResult.spamScore(judgment.spamScore(), spam));
    }

    private void processClassification(ProcessingStageContext context, PipelineSettings settings) {
        ClassificationMode mode = transition.classificationMode(settings);
        if (mode == ClassificationMode.SKIP) {
            completeWithoutResult(context, ProcessingStage.LANGUAGE_DETECTION);
            return;
        }
        ChatModel model = chatModelHolder.current();
        if (model == null) {
            completeWithoutResult(context, ProcessingStage.LANGUAGE_DETECTION);
            return;
        }

        boolean writeTags = mode == ClassificationMode.TAGS_ONLY
                || mode == ClassificationMode.CATEGORY_AND_TAGS;
        List<Tag> tags = writeTags
                ? tagMapper.selectList(Wrappers.lambdaQuery(Tag.class).orderByAsc(Tag::getId))
                : List.of();
        ClassificationResult classification = aiClient.classify(
                model, context.message(), tags, mode);
        ProcessingLease renewedLease = renewAfterRemote(context.lease());
        persistenceService.complete(
                renewedLease,
                context.stage(),
                ProcessingStage.LANGUAGE_DETECTION,
                ProcessingStageResult.classification(
                        classification.category(), classification.tagIds(), writeTags));
    }

    private void processLanguageDetection(ProcessingStageContext context, PipelineSettings settings) {
        if (!settings.languageTranslationEnabled()) {
            completeWithoutResult(context, ProcessingStage.SUMMARY);
            return;
        }
        LanguageDetection detection = languageDetector.detect(context.message().getBodyText());
        persistenceService.complete(
                context.lease(),
                context.stage(),
                transition.afterLanguageDetection(detection),
                ProcessingStageResult.none());
    }

    private void processTranslation(ProcessingStageContext context, PipelineSettings settings) {
        if (!settings.languageTranslationEnabled()
                || context.message().getBodyText() == null
                || context.message().getBodyText().isBlank()) {
            completeWithoutResult(context, ProcessingStage.SUMMARY);
            return;
        }
        ChatModel model = chatModelHolder.current();
        if (model == null) {
            completeWithoutResult(context, ProcessingStage.SUMMARY);
            return;
        }

        String translatedBody = aiClient.translate(model, context.message());
        ProcessingLease renewedLease = renewAfterRemote(context.lease());
        persistenceService.complete(
                renewedLease,
                context.stage(),
                ProcessingStage.SUMMARY,
                ProcessingStageResult.translation(translatedBody));
    }

    private void processSummary(ProcessingStageContext context, PipelineSettings settings) {
        if (!settings.summaryEnabled()
                || context.message().getBodyText() == null
                || context.message().getBodyText().isBlank()) {
            completeWithoutResult(context, ProcessingStage.DONE);
            return;
        }
        ChatModel model = chatModelHolder.current();
        if (model == null) {
            completeWithoutResult(context, ProcessingStage.DONE);
            return;
        }

        String summary = aiClient.summarize(model, context.message());
        ProcessingLease renewedLease = renewAfterRemote(context.lease());
        persistenceService.complete(
                renewedLease,
                context.stage(),
                ProcessingStage.DONE,
                ProcessingStageResult.summary(summary));
    }

    private void completeWithoutResult(
            ProcessingStageContext context,
            ProcessingStage nextStage) {
        persistenceService.complete(
                context.lease(), context.stage(), nextStage, ProcessingStageResult.none());
    }

    private ProcessingLease renewAfterRemote(ProcessingLease lease) {
        return persistenceService.renew(lease, PROCESSING_LEASE_DURATION)
                .orElseThrow(LostProcessingLeaseException::new);
    }

    private void failSafely(
            ProcessingStageContext context,
            String errorCode,
            int retryLimit) {
        try {
            persistenceService.fail(context.lease(), context.stage(), errorCode, retryLimit);
            log.error("邮件处理阶段失败: messageId={} stage={} errorCode={}",
                    context.lease().messageId(), context.stage().getValue(), errorCode);
        } catch (LostProcessingLeaseException ignored) {
            // 失败记录本身也必须服从 fencing；旧 worker 不得覆盖新领取者状态。
        }
    }

    private static boolean isAiStage(ProcessingStage stage) {
        return stage == ProcessingStage.SPAM_JUDGMENT
                || stage == ProcessingStage.CLASSIFICATION
                || stage == ProcessingStage.TRANSLATION
                || stage == ProcessingStage.SUMMARY;
    }
}
