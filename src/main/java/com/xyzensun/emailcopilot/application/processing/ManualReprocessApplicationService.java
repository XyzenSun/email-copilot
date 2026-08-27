package com.xyzensun.emailcopilot.application.processing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xyzensun.emailcopilot.application.mail.MailReadApplicationService;
import com.xyzensun.emailcopilot.application.mail.model.MessageDetailView;
import com.xyzensun.emailcopilot.application.settings.AppSettingService;
import com.xyzensun.emailcopilot.application.settings.PipelineSettings;
import com.xyzensun.emailcopilot.domain.enums.MessageCategory;
import com.xyzensun.emailcopilot.domain.enums.MessageDirection;
import com.xyzensun.emailcopilot.domain.pipeline.ClassificationMode;
import com.xyzensun.emailcopilot.domain.pipeline.LanguageDetection;
import com.xyzensun.emailcopilot.domain.pipeline.LanguageDetector;
import com.xyzensun.emailcopilot.infrastructure.ai.AiRuntimeSettings;
import com.xyzensun.emailcopilot.infrastructure.ai.ChatModelHolder;
import com.xyzensun.emailcopilot.infrastructure.ai.ClassificationResult;
import com.xyzensun.emailcopilot.infrastructure.ai.InvalidStructuredOutputException;
import com.xyzensun.emailcopilot.infrastructure.ai.PipelineAiClient;
import com.xyzensun.emailcopilot.infrastructure.ai.SpamJudgmentResult;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Message;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Tag;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MessageMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.TagMapper;
import com.xyzensun.emailcopilot.interfaces.error.ApiError;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import com.xyzensun.emailcopilot.interfaces.mail.dto.MessageDetailResponse;
import com.xyzensun.emailcopilot.interfaces.mail.dto.ReprocessResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 单封手动重新处理流水线某一步（阶段12）。用户对一封已入库的 inbound 邮件，单次触发流水线的
 * 某一步，<b>绕过该步的全局开关</b>（{@code design.md} §1/§6.2）。
 *
 * <p>与自动 {@link ProcessingApplicationService} 的根本区别：
 * <ul>
 *   <li><b>不推进游标</b>——只刷新产物列，{@code processing_progress} 的 stage/status/retry_count
 *       全程不动（{@code design.md} §5）。手动是用户对某一封的一次性补/重跑，不是自动流水线续跑。</li>
 *   <li><b>不调 fail()</b>——手动 AI 失败只释放租约 + 200+failed，不把 processing_progress 弄成
 *       failed/retry_count+1（{@code design.md} §5.4）。</li>
 *   <li><b>绕开关</b>——直接调 {@link PipelineAiClient}，不走 processXxx 的开关判断
 *       （{@code design.md} §6.2，这是本功能存在的理由）。</li>
 *   <li><b>按 id 领取</b>——用 {@link ProcessingPersistenceService#claimSpecific}，不靠队列选候选。</li>
 * </ul>
 *
 * <p>同步返回 200+status（{@code design.md} §3.2，与 approve 一致）：动作被尝试了，但 AI 可能
 * 返回不合规输出，记 failed 而非 5xx。只有「动作根本没能进行」（outbound/已删/不存在/AI 未配置/
 * 并发占用）才 4xx。
 */
@Service
public class ManualReprocessApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ManualReprocessApplicationService.class);

    /**
     * 租约复用自动流水线的同一基线（最大合法 AI 超时 + 60s 写回余量），手动单次 AI 调用
     * 不超过 {@link AiRuntimeSettings#MAX_TIMEOUT_SECONDS}，调用完仍有余量直接写回。
     */
    private static final Duration PROCESSING_LEASE_DURATION = Duration.ofSeconds(
            AiRuntimeSettings.MAX_TIMEOUT_SECONDS + 60L);
    private static final String ERROR_AI_PROVIDER = "AI_PROVIDER_FAILURE";
    private static final String ERROR_AI_OUTPUT = "AI_STRUCTURED_OUTPUT_INVALID";

    private final MessageMapper messageMapper;
    private final ProcessingPersistenceService persistenceService;
    private final PipelineAiClient aiClient;
    private final AppSettingService appSettingService;
    private final ChatModelHolder chatModelHolder;
    private final MailReadApplicationService mailReadApplicationService;
    private final TagMapper tagMapper;
    private final LanguageDetector languageDetector = new LanguageDetector();
    private final boolean enabled;
    private final String workerId = "manual-" + UUID.randomUUID();

    public ManualReprocessApplicationService(
            MessageMapper messageMapper,
            ProcessingPersistenceService persistenceService,
            PipelineAiClient aiClient,
            AppSettingService appSettingService,
            ChatModelHolder chatModelHolder,
            MailReadApplicationService mailReadApplicationService,
            TagMapper tagMapper,
            @Value("${email-copilot.reprocess.enabled:true}") boolean enabled) {
        this.messageMapper = messageMapper;
        this.persistenceService = persistenceService;
        this.aiClient = aiClient;
        this.appSettingService = appSettingService;
        this.chatModelHolder = chatModelHolder;
        this.mailReadApplicationService = mailReadApplicationService;
        this.tagMapper = tagMapper;
        this.enabled = enabled;
    }

    /**
     * 对指定邮件手动执行流水线的某一步。
     *
     * @param messageId 目标邮件
     * @param stage     要跑的步骤（已由 Controller 校验为四值之一）
     * @return 200+succeeded（产物已写回，message 是刷新详情）或 200+failed（AI 试了但没给出可用结果）
     * @throws ApiException 404 不存在/已删/已 purge/功能关闭；422 outbound；409 并发占用/AI 未配置/spam 拒重分类
     */
    public ReprocessResponse reprocess(long messageId, ReprocessStage stage) {
        if (!enabled) {
            // 功能关闭：让接口对前端「像不存在」（graceful 404，design.md §11）。
            throw new ApiException(ApiError.MESSAGE_NOT_FOUND);
        }

        Message message = messageMapper.selectById(messageId);
        if (message == null || message.getDeletedAt() != null) {
            throw new ApiException(ApiError.MESSAGE_NOT_FOUND);
        }
        if (message.getDirection() != MessageDirection.INBOUND) {
            throw new ApiException(ApiError.MESSAGE_NOT_INBOUND);
        }
        if (Boolean.TRUE.equals(message.getPurged())) {
            // purged 邮件正文已被 DataPurge 物理清空，无可处理内容。
            throw new ApiException(ApiError.MESSAGE_NOT_FOUND);
        }
        if (stage == ReprocessStage.CLASSIFICATION && message.getCategory() == MessageCategory.SPAM) {
            // 防 spam 洗白：垃圾邮件不可重跑分类（会覆盖 category=spam）。要纠正 spam 走垃圾评分重判。
            throw new ApiException(ApiError.MESSAGE_SPAM_RECLASSIFY_FORBIDDEN);
        }

        ChatModel model = chatModelHolder.current();
        if (model == null) {
            throw new ApiException(ApiError.AI_NOT_CONFIGURED);
        }
        PipelineSettings settings = appSettingService.getPipelineSettings();

        Optional<ProcessingLease> claimed = persistenceService.claimSpecific(
                messageId, workerId, PROCESSING_LEASE_DURATION);
        if (claimed.isEmpty()) {
            // 自动 worker 持租约或另一次手动在跑（CAS 领取返回 0 行)。
            throw new ApiException(ApiError.MESSAGE_REPROCESS_BUSY);
        }
        ProcessingLease lease = claimed.orElseThrow();

        ProcessingStageResult result;
        try {
            result = runStage(stage, message, model, settings);
            persistenceService.writeManualResult(lease, result);
        } catch (InvalidStructuredOutputException exception) {
            persistenceService.releaseLease(lease);
            log.error("手动重新处理失败: messageId={} stage={} errorCode={}",
                    messageId, stage.getValue(), ERROR_AI_OUTPUT);
            return failed(ERROR_AI_OUTPUT, messageId);
        } catch (LostProcessingLeaseException exception) {
            // 写回 0 行：邮件在处理期间被软删/purge，或租约恰好过期。统一当失败，不复活正文。
            persistenceService.releaseLease(lease);
            log.error("手动重新处理写回丢失租约: messageId={} stage={}", messageId, stage.getValue());
            return failed(ERROR_AI_PROVIDER, messageId);
        } catch (RuntimeException exception) {
            persistenceService.releaseLease(lease);
            log.error("手动重新处理失败: messageId={} stage={} errorCode={}",
                    messageId, stage.getValue(), ERROR_AI_PROVIDER);
            return failed(ERROR_AI_PROVIDER, messageId);
        }

        // 成功：取写回后的刷新详情。若此极小窗口内邮件被删，getMessage 抛 MESSAGE_NOT_FOUND → 404。
        MessageDetailView view = mailReadApplicationService.getMessage(messageId);
        return new ReprocessResponse(
                ReprocessStatus.SUCCEEDED.getValue(), null, MessageDetailResponse.from(view));
    }

    /**
     * 直接调 {@link PipelineAiClient} 对应方法，<b>绕过全局开关</b>（design.md §6.2）。
     * 不走 {@link ProcessingApplicationService} 的 processXxx 开关判断。
     */
    private ProcessingStageResult runStage(
            ReprocessStage stage, Message message, ChatModel model, PipelineSettings settings) {
        return switch (stage) {
            case SPAM_JUDGMENT -> runSpamJudgment(message, model, settings);
            case CLASSIFICATION -> runClassification(message, model);
            case TRANSLATION -> runTranslation(message, model);
            case SUMMARY -> runSummary(message, model);
        };
    }

    private ProcessingStageResult runSpamJudgment(Message message, ChatModel model, PipelineSettings settings) {
        SpamJudgmentResult judgment = aiClient.judgeSpam(model, message, settings.spamJudgmentPrompt());
        // SpamJudgmentResult 只给 spam_score；是否 spam 由当前阈值比较决定（与自动 processSpamJudgment 一致）。
        boolean spam = judgment.spamScore().compareTo(settings.spamClassificationThreshold()) >= 0;
        return ProcessingStageResult.spamScore(judgment.spamScore(), spam);
    }

    private ProcessingStageResult runClassification(Message message, ChatModel model) {
        // 手动分类同时写 category 与 tags（design.md §13.2），用 CATEGORY_AND_TAGS 忽略两个独立开关。
        List<Tag> tags = tagMapper.selectList(Wrappers.lambdaQuery(Tag.class).orderByAsc(Tag::getId));
        ClassificationResult classification = aiClient.classify(model, message, tags, ClassificationMode.CATEGORY_AND_TAGS);
        return ProcessingStageResult.classification(classification.category(), classification.tagIds(), true);
    }

    private ProcessingStageResult runTranslation(Message message, ChatModel model) {
        // 手动「翻译」内部先跑语言检测（确定性、非 AI）：中文/无字母不译，非中文才翻译。
        LanguageDetection detection = languageDetector.detect(message.getBodyText());
        if (detection == LanguageDetection.CHINESE || detection == LanguageDetection.UNKNOWN) {
            return ProcessingStageResult.none();
        }
        String translatedBody = aiClient.translate(model, message);
        return ProcessingStageResult.translation(translatedBody);
    }

    private ProcessingStageResult runSummary(Message message, ChatModel model) {
        String summary = aiClient.summarize(model, message);
        return ProcessingStageResult.summary(summary);
    }

    private ReprocessResponse failed(String errorCode, long messageId) {
        // 产物未改，取当前详情让前端就地展示。若邮件已被删，getMessage 抛 MESSAGE_NOT_FOUND → 404。
        MessageDetailView view = mailReadApplicationService.getMessage(messageId);
        return new ReprocessResponse(
                ReprocessStatus.FAILED.getValue(), errorCode, MessageDetailResponse.from(view));
    }
}
