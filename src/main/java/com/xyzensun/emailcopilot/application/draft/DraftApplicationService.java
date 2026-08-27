package com.xyzensun.emailcopilot.application.draft;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xyzensun.emailcopilot.application.draft.model.DraftView;
import com.xyzensun.emailcopilot.domain.AttachmentMeta;
import com.xyzensun.emailcopilot.domain.Recipients;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Draft;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MailAccount;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Message;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.DraftMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MailAccountMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MessageMapper;
import com.xyzensun.emailcopilot.interfaces.error.ApiError;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import com.xyzensun.emailcopilot.interfaces.error.ValidationErrorItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 草稿 CRUD（design.md §6.1）。
 *
 * <p><b>用户自己保存草稿不经审批</b>：审批闸门是给 AI 设的。用户点保存就是用户意图。
 *
 * <p><b>conversationId/inReplyToMessageId 不可改</b>（DRAFT_ORIGIN_IMMUTABLE）：
 * 它们是草稿的出身，改了会让"回复哪封"错位——回复头由代码从 inReplyToMessageId 拼。
 */
@Service
public class DraftApplicationService {

    private static final Pattern EMAIL_ADDRESS = Pattern.compile(
            "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final DraftMapper draftMapper;
    private final MailAccountMapper mailAccountMapper;
    private final MessageMapper messageMapper;

    public DraftApplicationService(
            DraftMapper draftMapper,
            MailAccountMapper mailAccountMapper,
            MessageMapper messageMapper) {
        this.draftMapper = draftMapper;
        this.mailAccountMapper = mailAccountMapper;
        this.messageMapper = messageMapper;
    }

    @Transactional(readOnly = true)
    public List<DraftView> listDrafts(int page, int size) {
        int offset = Math.max(0, page) * size;
        List<Draft> drafts = draftMapper.selectList(
                Wrappers.lambdaQuery(Draft.class)
                        .orderByDesc(Draft::getUpdatedAt)
                        .last("limit " + size + " offset " + offset));
        return drafts.stream().map(this::toView).toList();
    }

    @Transactional
    public DraftView createDraft(
            Long conversationId,
            Long inReplyToMessageId,
            long fromMailAccountId,
            Recipients recipients,
            String subject,
            String bodyText) {
        requireMailAccount(fromMailAccountId);
        if (inReplyToMessageId != null) {
            requireVisibleMessage(inReplyToMessageId);
        }
        validateRecipientAddresses(recipients);

        Draft draft = new Draft();
        draft.setConversationId(conversationId);
        draft.setInReplyToMessageId(inReplyToMessageId);
        draft.setFromMailAccountId(fromMailAccountId);
        draft.setRecipients(recipients != null ? recipients : Recipients.empty());
        draft.setSubject(subject != null ? subject : "");
        draft.setBodyText(bodyText != null ? bodyText : "");
        draft.setAttachmentMeta(List.of());
        draftMapper.insert(draft);
        return toView(draft);
    }

    @Transactional(readOnly = true)
    public DraftView getDraft(long id) {
        Draft draft = requireDraft(id);
        return toView(draft);
    }

    @Transactional
    public DraftView updateDraft(
            long id,
            Long fromMailAccountId,
            Recipients recipients,
            String subject,
            String bodyText) {
        Draft draft = requireDraft(id);
        if (fromMailAccountId != null) {
            requireMailAccount(fromMailAccountId);
            draft.setFromMailAccountId(fromMailAccountId);
        }
        if (recipients != null) {
            validateRecipientAddresses(recipients);
            draft.setRecipients(recipients);
        }
        if (subject != null) {
            draft.setSubject(subject);
        }
        if (bodyText != null) {
            draft.setBodyText(bodyText);
        }
        draftMapper.updateById(draft);
        return toView(draft);
    }

    @Transactional
    public void deleteDraft(long id) {
        Draft draft = requireDraft(id);
        draftMapper.deleteById(id);
    }

    private Draft requireDraft(long id) {
        Draft draft = draftMapper.selectById(id);
        if (draft == null) {
            throw new ApiException(ApiError.DRAFT_NOT_FOUND);
        }
        return draft;
    }

    private DraftView toView(Draft draft) {
        String inReplyToSubject = null;
        if (draft.getInReplyToMessageId() != null) {
            Message original = messageMapper.selectOne(
                    Wrappers.lambdaQuery(Message.class)
                            .eq(Message::getId, draft.getInReplyToMessageId())
                            .isNull(Message::getDeletedAt));
            if (original != null) {
                inReplyToSubject = original.getSubject();
            }
        }
        return new DraftView(
                draft.getId(),
                draft.getConversationId(),
                draft.getInReplyToMessageId(),
                inReplyToSubject,
                draft.getFromMailAccountId(),
                draft.getRecipients(),
                draft.getSubject(),
                draft.getBodyText(),
                draft.getUpdatedAt());
    }

    private void requireMailAccount(long mailAccountId) {
        MailAccount account = mailAccountMapper.selectById(mailAccountId);
        if (account == null) {
            throw new ApiException(ApiError.MAIL_ACCOUNT_NOT_FOUND);
        }
    }

    private void requireVisibleMessage(long messageId) {
        Message message = messageMapper.selectOne(
                Wrappers.lambdaQuery(Message.class)
                        .eq(Message::getId, messageId)
                        .isNull(Message::getDeletedAt));
        if (message == null) {
            throw new ApiException(ApiError.MESSAGE_NOT_FOUND);
        }
    }

    private static void validateRecipientAddresses(Recipients recipients) {
        if (recipients == null) {
            return;
        }
        List<ValidationErrorItem> errors = new ArrayList<>();
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
}
