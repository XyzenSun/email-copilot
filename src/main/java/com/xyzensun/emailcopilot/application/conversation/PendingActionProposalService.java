package com.xyzensun.emailcopilot.application.conversation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xyzensun.emailcopilot.domain.Recipients;
import com.xyzensun.emailcopilot.domain.conversation.CanonicalPayloadHasher;
import com.xyzensun.emailcopilot.domain.enums.ActionType;
import com.xyzensun.emailcopilot.domain.enums.ApprovalStatus;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.AppSetting;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MailAccount;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Message;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.PendingAction;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.PendingActionContent;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.AppSettingMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MailAccountMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MessageMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.PendingActionContentMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.PendingActionMapper;
import com.xyzensun.emailcopilot.interfaces.error.ApiError;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import com.xyzensun.emailcopilot.interfaces.error.ValidationErrorItem;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 提案工具执行器：只创建 {@link PendingAction}，<b>绝不连 SMTP/删除</b>（design.md §4.3、§9.1）。
 *
 * <p>提案参数必须重新经过业务校验，不得相信模型已验证目标地址/身份/对象状态
 * （{@code DATABASE.md} §5.5）。
 *
 * <p><b>幂等键</b>（design.md §3.2）：主键 {@code (turnId, providerToolCallId)}，兜底键
 * {@code (turnId, actionType, canonicalPayloadHash)}。靠唯一约束做幂等，不写"先查再插"。
 * 唯一约束冲突时返回已存在的提案 id，不重复创建。
 */
@Service
public class PendingActionProposalService {

    private static final short SETTINGS_ROW_ID = 1;
    // RFC 5321 addr-spec 的简化校验：local-part@domain。模型给的地址可能畸形，须由应用兜底。
    private static final Pattern EMAIL_ADDRESS = Pattern.compile(
            "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final PendingActionMapper pendingActionMapper;
    private final PendingActionContentMapper contentMapper;
    private final AppSettingMapper appSettingMapper;
    private final MailAccountMapper mailAccountMapper;
    private final MessageMapper messageMapper;
    private final Clock clock;

    public PendingActionProposalService(
            PendingActionMapper pendingActionMapper,
            PendingActionContentMapper contentMapper,
            AppSettingMapper appSettingMapper,
            MailAccountMapper mailAccountMapper,
            MessageMapper messageMapper,
            Clock clock) {
        this.pendingActionMapper = pendingActionMapper;
        this.contentMapper = contentMapper;
        this.appSettingMapper = appSettingMapper;
        this.mailAccountMapper = mailAccountMapper;
        this.messageMapper = messageMapper;
        this.clock = clock;
    }

    /**
     * 创建发信提案 + 不可变内容快照。
     *
     * @param providerToolCallId 框架工具调用 ID（主幂等键）；可能为 null
     * @return 已创建或已存在的 pending_action id
     */
    @Transactional
    public long createSendEmailProposal(
            long turnId,
            String providerToolCallId,
            long fromMailAccountId,
            Long inReplyToMessageId,
            Recipients recipients,
            String subject,
            String bodyText) {
        validateRecipients(recipients);
        validateSubjectBody(subject, bodyText);
        requireMailAccount(fromMailAccountId);
        if (inReplyToMessageId != null) {
            requireVisibleMessage(inReplyToMessageId);
        }
        String hash = CanonicalPayloadHasher.forSendEmailOrDraft(
                "send_email", fromMailAccountId, inReplyToMessageId,
                recipients, subject, bodyText);
        long expiresAtHours = getPendingActionTtlHours();
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(Duration.ofHours(expiresAtHours));

        InsertResult result = insertPendingAction(
                turnId, providerToolCallId, hash, expiresAt,
                ActionType.SEND_EMAIL, List.of());

        // 仅新提案才写内容快照；幂等返回已存在提案时不重复写（PK = pending_action.id）。
        if (result.newlyCreated()) {
            insertContent(result.pendingActionId(), fromMailAccountId,
                    inReplyToMessageId, recipients, subject, bodyText);
        }
        return result.pendingActionId();
    }

    /**
     * 创建草稿提案 + 不可变内容快照。
     */
    @Transactional
    public long createSaveDraftProposal(
            long turnId,
            String providerToolCallId,
            long fromMailAccountId,
            Long inReplyToMessageId,
            Recipients recipients,
            String subject,
            String bodyText) {
        validateRecipients(recipients);
        validateSubjectBody(subject, bodyText);
        requireMailAccount(fromMailAccountId);
        if (inReplyToMessageId != null) {
            requireVisibleMessage(inReplyToMessageId);
        }
        String hash = CanonicalPayloadHasher.forSendEmailOrDraft(
                "save_draft", fromMailAccountId, inReplyToMessageId,
                recipients, subject, bodyText);
        long expiresAtHours = getPendingActionTtlHours();
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(Duration.ofHours(expiresAtHours));

        InsertResult result = insertPendingAction(
                turnId, providerToolCallId, hash, expiresAt,
                ActionType.SAVE_DRAFT, List.of());

        if (result.newlyCreated()) {
            insertContent(result.pendingActionId(), fromMailAccountId,
                    inReplyToMessageId, recipients, subject, bodyText);
        }
        return result.pendingActionId();
    }

    /**
     * 创建本地删除提案。target_message_ids 在<b>创建时</b>展开为具体邮件 id（bigint[]），
     * 排序去重后写入；不允许存"会话 id"让执行时再展开。
     */
    @Transactional
    public long createLocalDeleteProposal(
            long turnId,
            String providerToolCallId,
            List<Long> targetMessageIds) {
        if (targetMessageIds == null || targetMessageIds.isEmpty()) {
            throw ApiException.validationFailed(List.of(
                    new ValidationErrorItem("targetMessageIds", "删除目标不能为空")));
        }
        // 排序去重：数组列不自动去重，写入前由应用处理。
        List<Long> sorted = new ArrayList<>(new LinkedHashSet<>(targetMessageIds));
        sorted.sort(Long::compare);

        // 验证目标邮件存在且可见（未删除、未清除正文标记）。
        for (Long messageId : sorted) {
            requireVisibleMessage(messageId);
        }

        String hash = CanonicalPayloadHasher.forLocalDelete(sorted);
        long expiresAtHours = getPendingActionTtlHours();
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(Duration.ofHours(expiresAtHours));

        return insertPendingAction(
                turnId, providerToolCallId, hash, expiresAt,
                ActionType.LOCAL_DELETE, sorted).pendingActionId();
    }

    /**
     * 插入 pending_action，靠唯一约束做幂等。
     *
     * <p>约束冲突时（provider_tool_call_id 或 canonical_payload_hash 重复）返回 0 行，
     * 此时查回已存在的提案 id，不重复创建。这是"靠约束做去重"模式（DATABASE §1.4）。
     *
     * <p><b>PostgreSQL 陷阱</b>：唯一约束冲突后 PG 把当前事务置为 aborted 状态，
     * 之后任何 SQL（包括查回已存在行的 SELECT）都会报
     * {@code current transaction is aborted}。因此用 savepoint 隔离 INSERT：
     * 冲突时先回滚到 savepoint 恢复事务可用状态，再查回已存在行。
     */
    private InsertResult insertPendingAction(
            long turnId,
            String providerToolCallId,
            String canonicalPayloadHash,
            OffsetDateTime expiresAt,
            ActionType actionType,
            List<Long> targetMessageIds) {
        PendingAction action = new PendingAction();
        action.setTurnId(turnId);
        action.setActionType(actionType);
        action.setApprovalStatus(ApprovalStatus.PENDING);
        action.setTargetMessageIds(targetMessageIds);
        action.setProviderToolCallId(providerToolCallId);
        action.setCanonicalPayloadHash(canonicalPayloadHash);
        action.setExpiresAt(expiresAt);

        // savepoint 隔离 INSERT：冲突时回滚到 savepoint 恢复事务，再查回已存在行。
        var txStatus = TransactionAspectSupport.currentTransactionStatus();
        Object savepoint = txStatus.createSavepoint();
        try {
            pendingActionMapper.insert(action);
            txStatus.releaseSavepoint(savepoint);
            return new InsertResult(action.getId(), true);
        } catch (DataIntegrityViolationException exception) {
            // 幂等键冲突：回滚到 savepoint 恢复事务可用状态，再查回已存在的提案。
            txStatus.rollbackToSavepoint(savepoint);
            long existingId = findExistingPendingActionId(
                    turnId, providerToolCallId, canonicalPayloadHash, actionType);
            return new InsertResult(existingId, false);
        }
    }

    /** 插入不可变内容快照（PK = pending_action.id，1:1）。 */
    private void insertContent(
            long pendingActionId, long fromMailAccountId, Long inReplyToMessageId,
            Recipients recipients, String subject, String bodyText) {
        PendingActionContent content = new PendingActionContent();
        content.setPendingActionId(pendingActionId);
        content.setFromMailAccountId(fromMailAccountId);
        content.setInReplyToMessageId(inReplyToMessageId);
        content.setRecipients(recipients);
        content.setSubject(subject);
        content.setBodyText(bodyText);
        content.setAttachmentMeta(List.of());
        contentMapper.insert(content);
    }

    /** insertPendingAction 的返回值：区分新插入与幂等返回已存在。 */
    private record InsertResult(long pendingActionId, boolean newlyCreated) {
    }

    private long findExistingPendingActionId(
            long turnId,
            String providerToolCallId,
            String canonicalPayloadHash,
            ActionType actionType) {
        // 优先按主键查。
        if (providerToolCallId != null) {
            PendingAction existing = pendingActionMapper.selectOne(
                    Wrappers.lambdaQuery(PendingAction.class)
                            .eq(PendingAction::getTurnId, turnId)
                            .eq(PendingAction::getProviderToolCallId, providerToolCallId));
            if (existing != null) {
                return existing.getId();
            }
        }
        // 兜底按 canonical payload hash 查。
        PendingAction existing = pendingActionMapper.selectOne(
                Wrappers.lambdaQuery(PendingAction.class)
                        .eq(PendingAction::getTurnId, turnId)
                        .eq(PendingAction::getActionType, actionType)
                        .eq(PendingAction::getCanonicalPayloadHash, canonicalPayloadHash));
        if (existing != null) {
            return existing.getId();
        }
        // 约束冲突但查不到：并发窗口间的极小概率，重新抛出让调用方决定。
        throw new IllegalStateException("提案幂等约束冲突但无法查回已存在行");
    }

    private void validateRecipients(Recipients recipients) {
        List<ValidationErrorItem> errors = new ArrayList<>();
        if (recipients.to().isEmpty() && recipients.cc().isEmpty() && recipients.bcc().isEmpty()) {
            errors.add(new ValidationErrorItem("recipients", "至少需要一个收件人"));
        }
        validateAddresses("recipients.to", recipients.to(), errors);
        validateAddresses("recipients.cc", recipients.cc(), errors);
        validateAddresses("recipients.bcc", recipients.bcc(), errors);
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
    }

    private static void validateAddresses(String field, List<String> addresses, List<ValidationErrorItem> errors) {
        for (int i = 0; i < addresses.size(); i++) {
            String address = addresses.get(i);
            if (address == null || address.isBlank() || !EMAIL_ADDRESS.matcher(address).matches()) {
                errors.add(new ValidationErrorItem(field + "[" + i + "]", "收件人地址格式非法"));
            }
        }
    }

    private static void validateSubjectBody(String subject, String bodyText) {
        List<ValidationErrorItem> errors = new ArrayList<>();
        if (subject == null || subject.isBlank()) {
            errors.add(new ValidationErrorItem("subject", "邮件主题不能为空"));
        }
        if (bodyText == null || bodyText.isBlank()) {
            errors.add(new ValidationErrorItem("bodyText", "邮件正文不能为空"));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
    }

    private void requireMailAccount(long mailAccountId) {
        MailAccount account = mailAccountMapper.selectById(mailAccountId);
        if (account == null) {
            throw ApiException.validationFailed(List.of(
                    new ValidationErrorItem("fromMailAccountId", "发信邮箱账号不存在")));
        }
    }

    private void requireVisibleMessage(long messageId) {
        Message message = messageMapper.selectOne(
                Wrappers.lambdaQuery(Message.class)
                        .eq(Message::getId, messageId)
                        .isNull(Message::getDeletedAt));
        if (message == null) {
            throw ApiException.validationFailed(List.of(
                    new ValidationErrorItem("inReplyToMessageId", "引用邮件不存在或已删除")));
        }
    }

    private int getPendingActionTtlHours() {
        AppSetting settings = appSettingMapper.selectById(SETTINGS_ROW_ID);
        if (settings == null) {
            return 24;
        }
        return settings.getPendingActionTtlHours();
    }
}
