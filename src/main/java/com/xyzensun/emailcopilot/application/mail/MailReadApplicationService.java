package com.xyzensun.emailcopilot.application.mail;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xyzensun.emailcopilot.application.mail.model.MessageDetailView;
import com.xyzensun.emailcopilot.application.mail.model.MessageListQuery;
import com.xyzensun.emailcopilot.application.mail.model.MessagePageView;
import com.xyzensun.emailcopilot.application.mail.model.MessageSummaryView;
import com.xyzensun.emailcopilot.application.mail.model.ThreadDetailView;
import com.xyzensun.emailcopilot.domain.enums.MessageCategory;
import com.xyzensun.emailcopilot.domain.enums.MessageDirection;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Attachment;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Message;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.AttachmentMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MessageMapper;
import com.xyzensun.emailcopilot.interfaces.error.ApiError;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import com.xyzensun.emailcopilot.interfaces.error.ValidationErrorItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 阶段 4A 的最小邮件/会话只读用例；HTML 只在 MIME 入库边界离线转换一次。 */
@Service
public class MailReadApplicationService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int SNIPPET_CODE_POINTS = 120;

    private final MessageMapper messageMapper;
    private final AttachmentMapper attachmentMapper;

    public MailReadApplicationService(
            MessageMapper messageMapper,
            AttachmentMapper attachmentMapper) {
        this.messageMapper = messageMapper;
        this.attachmentMapper = attachmentMapper;
    }

    @Transactional(readOnly = true)
    public MessagePageView listMessages(MessageListQuery query) {
        validateQuery(query);
        LambdaQueryWrapper<Message> countQuery = buildListQuery(query);
        long total = messageMapper.selectCount(countQuery);
        int offset = Math.multiplyExact(query.page() - 1, query.size());
        LambdaQueryWrapper<Message> pageQuery = buildListQuery(query)
                .orderByDesc(Message::getReceivedAt)
                .orderByDesc(Message::getId)
                .last("limit " + query.size() + " offset " + offset);
        List<Message> messages = messageMapper.selectList(pageQuery);
        Set<Long> messageIdsWithAttachments = listMessageIdsWithAttachments(
                messages.stream().map(Message::getId).toList());
        List<MessageSummaryView> items = messages.stream()
                .map(message -> toSummary(
                        message, messageIdsWithAttachments.contains(message.getId())))
                .toList();
        return new MessagePageView(items, query.page(), query.size(), total);
    }

    @Transactional(readOnly = true)
    public MessageDetailView getMessage(long messageId) {
        Message message = messageMapper.selectOne(
                Wrappers.lambdaQuery(Message.class)
                        .eq(Message::getId, messageId)
                        .isNull(Message::getDeletedAt));
        if (message == null) {
            throw new ApiException(ApiError.MESSAGE_NOT_FOUND);
        }
        List<Attachment> attachments = attachmentMapper.selectList(
                Wrappers.lambdaQuery(Attachment.class)
                        .eq(Attachment::getMessageIdPk, messageId)
                        .orderByAsc(Attachment::getId));
        List<MessageDetailView.AttachmentView> attachmentViews = attachments.stream()
                .map(attachment -> new MessageDetailView.AttachmentView(
                        attachment.getId(),
                        plainText(attachment.getFilename()),
                        plainText(attachment.getContentType()),
                        attachment.getSizeBytes().longValue()))
                .toList();
        return new MessageDetailView(
                toSummary(message, !attachments.isEmpty()),
                plainText(message.getBodyText()),
                plainText(message.getTranslatedBody()),
                plainText(message.getSummary()),
                message.getSentAt(),
                message.getFromAuthenticatedDomain(),
                message.getSpamScore(),
                attachmentViews);
    }

    @Transactional(readOnly = true)
    public ThreadDetailView getThread(long threadNodeId) {
        List<Message> messages = messageMapper.selectList(
                Wrappers.lambdaQuery(Message.class)
                        .eq(Message::getThreadNodeId, threadNodeId)
                        .isNull(Message::getDeletedAt)
                        .and(wrapper -> wrapper
                                .isNull(Message::getCategory)
                                .or()
                                .ne(Message::getCategory, MessageCategory.SPAM))
                        .orderByAsc(Message::getReceivedAt)
                        .orderByAsc(Message::getId));
        if (messages.isEmpty()) {
            throw new ApiException(ApiError.THREAD_NOT_FOUND);
        }
        Set<Long> messageIdsWithAttachments = listMessageIdsWithAttachments(
                messages.stream().map(Message::getId).toList());
        List<MessageSummaryView> items = messages.stream()
                .map(message -> toSummary(
                        message, messageIdsWithAttachments.contains(message.getId())))
                .toList();
        return new ThreadDetailView(threadNodeId, items.size(), items);
    }

    private static LambdaQueryWrapper<Message> buildListQuery(MessageListQuery query) {
        LambdaQueryWrapper<Message> wrapper = Wrappers.lambdaQuery(Message.class)
                .isNull(Message::getDeletedAt);
        if (query.accountId() != null) {
            wrapper.eq(Message::getMailAccountId, query.accountId());
        }
        if (query.category() != null) {
            wrapper.eq(Message::getCategory, query.category());
        }
        if (query.tagId() != null) {
            wrapper.apply("tags @> ARRAY[CAST({0} AS bigint)]", query.tagId());
        }
        if (query.direction() == MessageListQuery.DirectionSelection.INBOUND) {
            wrapper.eq(Message::getDirection, MessageDirection.INBOUND);
        } else if (query.direction() == MessageListQuery.DirectionSelection.OUTBOUND) {
            wrapper.eq(Message::getDirection, MessageDirection.OUTBOUND);
        }
        if (query.receivedAfter() != null) {
            wrapper.ge(Message::getReceivedAt, query.receivedAfter());
        }
        if (query.receivedBefore() != null) {
            wrapper.le(Message::getReceivedAt, query.receivedBefore());
        }
        if (!query.includeSpam()) {
            // SQL 的 NULL <> 'spam' 结果为 UNKNOWN；显式保留尚未分类的邮件。
            wrapper.and(category -> category
                    .isNull(Message::getCategory)
                    .or()
                    .ne(Message::getCategory, MessageCategory.SPAM));
        }
        return wrapper;
    }

    private Set<Long> listMessageIdsWithAttachments(List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return Set.of();
        }
        List<Attachment> attachments = attachmentMapper.selectList(
                Wrappers.lambdaQuery(Attachment.class)
                        .in(Attachment::getMessageIdPk, messageIds)
                        .select(Attachment::getMessageIdPk));
        Set<Long> result = new HashSet<>();
        attachments.forEach(attachment -> result.add(attachment.getMessageIdPk()));
        return result;
    }

    static MessageSummaryView toSummary(Message message, boolean hasAttachment) {
        String summary = plainText(message.getSummary());
        String fallbackBody = plainText(message.getBodyText());
        return toSummary(
                message,
                hasAttachment,
                truncateByCodePoint(summary == null ? fallbackBody : summary, SNIPPET_CODE_POINTS));
    }

    static MessageSummaryView toSummary(
            Message message,
            boolean hasAttachment,
            String snippet) {
        return new MessageSummaryView(
                message.getId(),
                message.getThreadNodeId(),
                message.getMailAccountId(),
                message.getDirection().getValue(),
                plainText(message.getFromDisplay()),
                message.getFromAddress(),
                message.getRecipients(),
                plainText(message.getSubject()),
                snippet,
                message.getReceivedAt(),
                message.getCategory() == null ? null : message.getCategory().getValue(),
                message.getTags() == null ? List.of() : List.copyOf(message.getTags()),
                hasAttachment,
                message.getDkimPassed());
    }

    private static String plainText(String value) {
        // 入库字段已经是纯文本。再次按 HTML 解析会损坏合法内容，例如 Message-ID
        // “<parent@example.com>”或数学表达式“a < b”，因此读取边界必须原样返回。
        return value;
    }

    private static String truncateByCodePoint(String value, int limit) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int count = value.codePointCount(0, value.length());
        if (count <= limit) {
            return value;
        }
        int end = value.offsetByCodePoints(0, limit);
        return value.substring(0, end);
    }

    private static void validateQuery(MessageListQuery query) {
        List<ValidationErrorItem> errors = new ArrayList<>();
        if (query.page() < 1) {
            errors.add(new ValidationErrorItem("page", "必须从 1 起"));
        }
        if (query.size() < 1 || query.size() > MAX_PAGE_SIZE) {
            errors.add(new ValidationErrorItem("size", "必须在 1 到 100 之间"));
        }
        if (query.accountId() != null && query.accountId() <= 0) {
            errors.add(new ValidationErrorItem("accountId", "必须为正数"));
        }
        if (query.tagId() != null && query.tagId() <= 0) {
            errors.add(new ValidationErrorItem("tagId", "必须为正数"));
        }
        if (query.receivedAfter() != null
                && query.receivedBefore() != null
                && query.receivedAfter().isAfter(query.receivedBefore())) {
            errors.add(new ValidationErrorItem(
                    "receivedAfter", "不能晚于 receivedBefore"));
        }
        if (query.direction() == null) {
            errors.add(new ValidationErrorItem("direction", "方向不能为空"));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
    }
}
