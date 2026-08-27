package com.xyzensun.emailcopilot.application.approval;

import com.xyzensun.emailcopilot.application.approval.model.ApprovalResultView;
import com.xyzensun.emailcopilot.application.mail.MessageDeletionApplicationService;
import com.xyzensun.emailcopilot.application.mail.OutboundMessageIngester;
import com.xyzensun.emailcopilot.application.mail.model.BatchDeleteResult;
import com.xyzensun.emailcopilot.domain.AttachmentMeta;
import com.xyzensun.emailcopilot.domain.Recipients;
import com.xyzensun.emailcopilot.domain.enums.ActionType;
import com.xyzensun.emailcopilot.domain.enums.ApprovalStatus;
import com.xyzensun.emailcopilot.infrastructure.mail.SmtpSendOutcome;
import com.xyzensun.emailcopilot.infrastructure.mail.SmtpUnavailableException;
import com.xyzensun.emailcopilot.infrastructure.mail.SmtpMailSender;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.ActionExecution;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.AppSetting;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Draft;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MailAccount;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.PendingAction;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.PendingActionContent;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.ActionExecutionMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.AppSettingMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.DraftMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MailAccountMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.PendingActionContentMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.PendingActionMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.TurnMapper;
import com.xyzensun.emailcopilot.infrastructure.security.ExternalAccountSecretStore;
import com.xyzensun.emailcopilot.domain.enums.SecretType;
import com.xyzensun.emailcopilot.interfaces.error.ApiError;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 审批消费与执行（design.md §4）。
 *
 * <p><b>批准与 ActionExecution 创建同一事务</b>（DECISIONS §6）：CAS 一次一用，
 * 返回一行则同事务 INSERT action_execution(executing)，返回零行则 409 不执行。
 *
 * <p><b>发信顺序不能变</b>（ARCHITECTURE §6.2）：
 * <ul>
 *   <li>send_email：短事务1（CAS+executing）→ 事务外 SMTP → 短事务2（记终态+入库）。</li>
 *   <li>save_draft/local_delete：本地数据库事务，同事务直达 succeeded，indeterminate 不可能发生。</li>
 * </ul>
 *
 * <p><b>approve 没有 503</b>：openapi 只定义 200/401/403/404/409。SMTP 连不上时
 * 批准已消费，执行记 failed，返回 200+failed（用户需让 AI 重新生成提案）。
 * 503 SMTP_UNAVAILABLE 只属于 {@code POST /send}（未消费批准、可安全重试）。
 */
@Service
public class ApprovalApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalApplicationService.class);
    private static final short SETTINGS_ROW_ID = 1;
    private static final int MIN_SMTP_TIMEOUT_SECONDS = 5;

    private final PendingActionMapper pendingActionMapper;
    private final PendingActionContentMapper contentMapper;
    private final ActionExecutionMapper actionExecutionMapper;
    private final MailAccountMapper mailAccountMapper;
    private final AppSettingMapper appSettingMapper;
    private final DraftMapper draftMapper;
    private final TurnMapper turnMapper;
    private final ExternalAccountSecretStore secretStore;
    private final SmtpMailSender smtpMailSender;
    private final OutboundMessageIngester outboundMessageIngester;
    private final MessageDeletionApplicationService messageDeletionApplicationService;
    private final TransactionTemplate transactionTemplate;

    public ApprovalApplicationService(
            PendingActionMapper pendingActionMapper,
            PendingActionContentMapper contentMapper,
            ActionExecutionMapper actionExecutionMapper,
            MailAccountMapper mailAccountMapper,
            AppSettingMapper appSettingMapper,
            DraftMapper draftMapper,
            TurnMapper turnMapper,
            ExternalAccountSecretStore secretStore,
            SmtpMailSender smtpMailSender,
            OutboundMessageIngester outboundMessageIngester,
            MessageDeletionApplicationService messageDeletionApplicationService,
            TransactionTemplate transactionTemplate) {
        this.pendingActionMapper = pendingActionMapper;
        this.contentMapper = contentMapper;
        this.actionExecutionMapper = actionExecutionMapper;
        this.mailAccountMapper = mailAccountMapper;
        this.appSettingMapper = appSettingMapper;
        this.draftMapper = draftMapper;
        this.turnMapper = turnMapper;
        this.secretStore = secretStore;
        this.smtpMailSender = smtpMailSender;
        this.outboundMessageIngester = outboundMessageIngester;
        this.messageDeletionApplicationService = messageDeletionApplicationService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 批准提案。同步等到执行有结果才返回。
     *
     * <p>send_email 走三步（CAS→SMTP→记终态），save_draft/local_delete 同事务直达 succeeded。
     * execution.status 可能是 failed/indeterminate——仍是 HTTP 200。
     */
    public ApprovalResultView approve(long pendingActionId) {
        PendingAction action = pendingActionMapper.selectById(pendingActionId);
        if (action == null) {
            throw new ApiException(ApiError.PENDING_ACTION_NOT_FOUND);
        }

        return switch (action.getActionType()) {
            case SEND_EMAIL -> approveSendEmail(pendingActionId);
            case SAVE_DRAFT -> approveSaveDraft(pendingActionId);
            case LOCAL_DELETE -> approveLocalDelete(pendingActionId);
        };
    }

    /**
     * 拒绝提案。不带请求体，走 CAS，execution 恒 null。
     */
    public ApprovalResultView reject(long pendingActionId) {
        int affected = pendingActionMapper.rejectCas(pendingActionId);
        if (affected == 0) {
            throwCasFailure(pendingActionId);
        }
        PendingAction action = pendingActionMapper.selectById(pendingActionId);
        return new ApprovalResultView(
                pendingActionId,
                ApprovalStatus.REJECTED.getValue(),
                action.getDecidedAt(),
                null);
    }

    private ApprovalResultView approveSendEmail(long pendingActionId) {
        // 短事务1：CAS 消费批准 + INSERT action_execution(executing)
        transactionTemplate.executeWithoutResult(status -> {
            int affected = pendingActionMapper.approveCas(pendingActionId);
            if (affected == 0) {
                throwCasFailure(pendingActionId);
            }
            actionExecutionMapper.insertExecuting(pendingActionId);
        });

        // 事务外：读不可变快照 + 构造 MimeMessage + SMTP 提交
        PendingActionContent content = contentMapper.selectById(pendingActionId);
        if (content == null) {
            return finalizeFailed(pendingActionId, "审批快照缺失");
        }

        MailAccount account = mailAccountMapper.selectById(content.getFromMailAccountId());
        if (account == null) {
            return finalizeFailed(pendingActionId, "发信账号不存在");
        }

        SmtpSendOutcome outcome = executeSmtpSend(account, content);
        OffsetDateTime sentAt = OffsetDateTime.now();

        // 短事务2：记终态；succeeded 时入库 outbound + Lucene + 归并
        Long outboundMessageId = transactionTemplate.execute(status -> {
            actionExecutionMapper.updateTerminal(
                    pendingActionId,
                    outcome.status().name().toLowerCase(Locale.ROOT),
                    outcome.serverMessage());
            if (outcome.status() == SmtpSendOutcome.Status.SUCCEEDED) {
                OutboundMessageIngester.ReplyHeaders replyHeaders =
                        outboundMessageIngester.resolveReplyHeaders(content.getInReplyToMessageId());
                List<String> referenceChain = replyHeaders != null
                        ? replyHeaders.referenceChain() : List.of();
                return outboundMessageIngester.ingest(new OutboundMessageIngester.OutboundCommand(
                        account.getId(),
                        outcome.rfcMessageId(),
                        account.getEmailAddress(),
                        resolveDisplayName(account),
                        content.getRecipients(),
                        content.getSubject(),
                        content.getBodyText(),
                        sentAt,
                        referenceChain));
            }
            return null;
        });

        return buildApprovalResult(pendingActionId, outcome, outboundMessageId);
    }

    private ApprovalResultView approveSaveDraft(long pendingActionId) {
        // 同事务直达 succeeded：CAS + executing + 写 draft + terminal
        return transactionTemplate.execute(status -> {
            int affected = pendingActionMapper.approveCas(pendingActionId);
            if (affected == 0) {
                throwCasFailure(pendingActionId);
            }
            actionExecutionMapper.insertExecuting(pendingActionId);

            PendingAction action = pendingActionMapper.selectById(pendingActionId);
            PendingActionContent content = contentMapper.selectById(pendingActionId);
            if (content == null) {
                actionExecutionMapper.updateTerminal(pendingActionId, "failed", "审批快照缺失");
                return buildResult(pendingActionId, "failed", "审批快照缺失");
            }

            Draft draft = new Draft();
            draft.setConversationId(resolveConversationId(action.getTurnId()));
            draft.setInReplyToMessageId(content.getInReplyToMessageId());
            draft.setFromMailAccountId(content.getFromMailAccountId());
            draft.setRecipients(content.getRecipients());
            draft.setSubject(content.getSubject());
            draft.setBodyText(content.getBodyText());
            draft.setAttachmentMeta(List.of());
            draftMapper.insert(draft);

            actionExecutionMapper.updateTerminal(pendingActionId, "succeeded", "草稿已保存");
            return buildResult(pendingActionId, "succeeded", "草稿已保存");
        });
    }

    private ApprovalResultView approveLocalDelete(long pendingActionId) {
        // 同事务直达 succeeded：CAS + executing + 软删 + terminal
        return transactionTemplate.execute(status -> {
            int affected = pendingActionMapper.approveCas(pendingActionId);
            if (affected == 0) {
                throwCasFailure(pendingActionId);
            }
            actionExecutionMapper.insertExecuting(pendingActionId);

            PendingAction action = pendingActionMapper.selectById(pendingActionId);
            List<Long> targetIds = action.getTargetMessageIds() != null
                    ? action.getTargetMessageIds() : List.of();
            // 走阶段11 删除服务：同步清正文 + 留骨架防复活（与 UI 删除一致）。
            // 原先 softDeleteMessages 只写 deleted_at 不清正文，已废弃。
            BatchDeleteResult deleteResult = messageDeletionApplicationService.batchDelete(targetIds);

            String resultMessage = "已删除 " + deleteResult.deleted() + " 封邮件"
                    + (deleteResult.alreadyDeleted() > 0
                            ? "（" + deleteResult.alreadyDeleted() + " 封此前已删除）" : "");
            actionExecutionMapper.updateTerminal(pendingActionId, "succeeded", resultMessage);
            return buildResult(pendingActionId, "succeeded", resultMessage);
        });
    }

    private SmtpSendOutcome executeSmtpSend(MailAccount account, PendingActionContent content) {
        if (!Boolean.TRUE.equals(account.getSmtpEnabled())) {
            return SmtpSendOutcome.failed("SMTP 未启用", null);
        }
        String password = secretStore.load(SecretType.SMTP_PASSWORD, account.getId())
                .orElse(null);
        if (password == null) {
            return SmtpSendOutcome.failed("SMTP 口令未配置", null);
        }

        OutboundMessageIngester.ReplyHeaders replyHeaders =
                outboundMessageIngester.resolveReplyHeaders(content.getInReplyToMessageId());
        String rfcMessageId = generateRfcMessageId(account.getEmailAddress());
        String inReplyTo = replyHeaders != null ? replyHeaders.inReplyTo() : null;
        String references = replyHeaders != null ? replyHeaders.references() : null;

        int timeoutMillis = resolveSmtpTimeoutMillis();
        try {
            return smtpMailSender.send(
                    account, password, content.getRecipients(),
                    content.getSubject(), content.getBodyText(),
                    rfcMessageId, inReplyTo, references, timeoutMillis);
        } catch (SmtpUnavailableException exception) {
            // approve 没有 503：批准已消费，执行记 failed。
            return SmtpSendOutcome.failed("SMTP 连接失败", rfcMessageId);
        }
    }

    private ApprovalResultView finalizeFailed(long pendingActionId, String resultMessage) {
        transactionTemplate.executeWithoutResult(status ->
                actionExecutionMapper.updateTerminal(pendingActionId, "failed", resultMessage));
        return buildResult(pendingActionId, "failed", resultMessage);
    }

    private void throwCasFailure(long pendingActionId) {
        PendingAction action = pendingActionMapper.selectById(pendingActionId);
        if (action == null) {
            throw new ApiException(ApiError.PENDING_ACTION_NOT_FOUND);
        }
        if (action.getApprovalStatus() != ApprovalStatus.PENDING) {
            throw new ApiException(ApiError.PENDING_ACTION_ALREADY_DECIDED);
        }
        throw new ApiException(ApiError.PENDING_ACTION_EXPIRED);
    }

    private ApprovalResultView buildApprovalResult(
            long pendingActionId, SmtpSendOutcome outcome, Long outboundMessageId) {
        String resultMessage = outcome.serverMessage();
        if (outcome.status() == SmtpSendOutcome.Status.SUCCEEDED && outboundMessageId != null) {
            resultMessage = outcome.serverMessage();
        }
        return buildResult(pendingActionId,
                outcome.status().name().toLowerCase(Locale.ROOT),
                resultMessage);
    }

    private ApprovalResultView buildResult(long pendingActionId, String executionStatus, String resultMessage) {
        PendingAction action = pendingActionMapper.selectById(pendingActionId);
        ActionExecution execution = actionExecutionMapper.selectById(pendingActionId);
        ApprovalResultView.ExecutionView executionView = new ApprovalResultView.ExecutionView(
                executionStatus,
                execution.getStartedAt(),
                execution.getFinishedAt(),
                resultMessage);
        return new ApprovalResultView(
                pendingActionId,
                ApprovalStatus.APPROVED.getValue(),
                action.getDecidedAt(),
                executionView);
    }

    private Long resolveConversationId(Long turnId) {
        if (turnId == null) {
            return null;
        }
        var turn = turnMapper.selectById(turnId);
        return turn != null ? turn.getConversationId() : null;
    }

    private int resolveSmtpTimeoutMillis() {
        AppSetting setting = appSettingMapper.selectById(SETTINGS_ROW_ID);
        int timeoutSeconds = setting != null && setting.getSmtpTimeoutSeconds() != null
                ? setting.getSmtpTimeoutSeconds() : 20;
        return Math.max(MIN_SMTP_TIMEOUT_SECONDS, timeoutSeconds) * 1000;
    }

    private static String resolveDisplayName(MailAccount account) {
        if (account.getDisplayName() != null && !account.getDisplayName().isBlank()) {
            return account.getDisplayName();
        }
        return account.getEmailAddress();
    }

    private static String generateRfcMessageId(String fromAddress) {
        String domain = extractDomain(fromAddress);
        return "<" + UUID.randomUUID() + "@" + domain + ">";
    }

    private static String extractDomain(String emailAddress) {
        int atIndex = emailAddress.lastIndexOf('@');
        if (atIndex < 0 || atIndex == emailAddress.length() - 1) {
            return emailAddress.toLowerCase(Locale.ROOT);
        }
        return emailAddress.substring(atIndex + 1).toLowerCase(Locale.ROOT);
    }
}
