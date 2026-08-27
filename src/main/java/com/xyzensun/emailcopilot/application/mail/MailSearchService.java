package com.xyzensun.emailcopilot.application.mail;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xyzensun.emailcopilot.application.mail.model.MailSearchQuery;
import com.xyzensun.emailcopilot.application.mail.model.MessagePageView;
import com.xyzensun.emailcopilot.application.mail.model.MessageSummaryView;
import com.xyzensun.emailcopilot.domain.enums.MessageCategory;
import com.xyzensun.emailcopilot.domain.enums.MessageDirection;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.AppSetting;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Attachment;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Message;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.AppSettingMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.AttachmentMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MessageMapper;
import com.xyzensun.emailcopilot.infrastructure.search.LuceneMailIndex;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import com.xyzensun.emailcopilot.interfaces.error.ValidationErrorItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 前端 LiteralSearch 与未来对话 AI RelevanceSearch 的统一应用服务。 */
@Service
public class MailSearchService {

    private static final short SETTINGS_ROW_ID = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int CANDIDATE_FILTER_BATCH_SIZE = 500;
    private static final int SNIPPET_CODE_POINTS = 120;
    private static final int SNIPPET_CONTEXT_BEFORE = 40;

    private final MessageMapper messageMapper;
    private final AttachmentMapper attachmentMapper;
    private final AppSettingMapper appSettingMapper;
    private final LuceneMailIndex mailIndex;

    public MailSearchService(
            MessageMapper messageMapper,
            AttachmentMapper attachmentMapper,
            AppSettingMapper appSettingMapper,
            LuceneMailIndex mailIndex) {
        this.messageMapper = messageMapper;
        this.attachmentMapper = attachmentMapper;
        this.appSettingMapper = appSettingMapper;
        this.mailIndex = mailIndex;
    }

    @Transactional(readOnly = true)
    public MessagePageView literalSearch(MailSearchQuery query) {
        validateLiteralQuery(query);
        if (query.field() == MailSearchQuery.SearchField.SENDER) {
            return searchSender(query);
        }

        boolean searchSubject = query.field() == MailSearchQuery.SearchField.ANY
                || query.field() == MailSearchQuery.SearchField.SUBJECT;
        boolean searchBody = query.field() == MailSearchQuery.SearchField.ANY
                || query.field() == MailSearchQuery.SearchField.BODY;
        List<Long> candidateIds = mailIndex.literalSearch(
                query.queryText(),
                searchSubject,
                searchBody,
                query.order() == MailSearchQuery.SortOrder.DESC,
                query.accountId(),
                toDirection(query.direction()),
                query.receivedAfter(),
                query.receivedBefore());
        List<Long> visibleIds = filterCandidateIds(candidateIds, query);
        return pageFromOrderedIds(visibleIds, query);
    }

    @Transactional(readOnly = true)
    public List<MailSearchQuery.RelevanceResult> relevanceSearch(
            MailSearchQuery.RelevanceQuery query) {
        validateRelevanceQuery(query);
        List<Long> candidateIds = mailIndex.relevanceSearch(
                query.queryText(),
                query.accountId(),
                toDirection(query.direction()),
                query.receivedAfter(),
                query.receivedBefore());
        List<Long> visibleIds = filterCandidateIds(candidateIds, query);
        AppSetting settings = appSettingMapper.selectById(SETTINGS_ROW_ID);
        int limit = settings.getSearchResultLimit();
        List<Long> limitedIds = visibleIds.subList(0, Math.min(limit, visibleIds.size()));
        Map<Long, Message> messagesById = loadMessagesById(limitedIds);
        List<String> terms = rawTerms(query.queryText());
        List<MailSearchQuery.RelevanceResult> result = new ArrayList<>(limitedIds.size());
        for (Long messageId : limitedIds) {
            Message message = messagesById.get(messageId);
            String matchedField = matchedField(message, terms);
            String snippet = switch (matchedField) {
                case "subject" -> snippetAround(message.getSubject(), terms);
                case "fromDisplay" -> truncateByCodePoint(message.getFromDisplay(), SNIPPET_CODE_POINTS);
                default -> snippetAround(message.getBodyText(), terms);
            };
            // subject/fromAddress/receivedAt 随已加载的 Message 一并传出，供 SSE evidence 事件富化。
            result.add(new MailSearchQuery.RelevanceResult(
                    messageId, matchedField, snippet,
                    message.getSubject(), message.getFromAddress(), message.getReceivedAt()));
        }
        return result;
    }

    private MessagePageView searchSender(MailSearchQuery query) {
        LambdaQueryWrapper<Message> countQuery = buildAuthorityQuery(query);
        addSenderPrefix(countQuery, query.queryText());
        long total = messageMapper.selectCount(countQuery);

        int offset = Math.multiplyExact(query.page() - 1, query.size());
        LambdaQueryWrapper<Message> pageQuery = buildAuthorityQuery(query);
        addSenderPrefix(pageQuery, query.queryText());
        if (query.order() == MailSearchQuery.SortOrder.DESC) {
            pageQuery.orderByDesc(Message::getReceivedAt).orderByDesc(Message::getId);
        } else {
            pageQuery.orderByAsc(Message::getReceivedAt).orderByAsc(Message::getId);
        }
        pageQuery.last("limit " + query.size() + " offset " + offset);
        List<Message> messages = messageMapper.selectList(pageQuery);
        Set<Long> withAttachments = listMessageIdsWithAttachments(
                messages.stream().map(Message::getId).toList());
        List<String> terms = rawTerms(query.queryText());
        List<MessageSummaryView> items = messages.stream()
                .map(message -> MailReadApplicationService.toSummary(
                        message,
                        withAttachments.contains(message.getId()),
                        senderSnippet(message, terms)))
                .toList();
        return new MessagePageView(items, query.page(), query.size(), total);
    }

    private MessagePageView pageFromOrderedIds(
            List<Long> visibleIds,
            MailSearchQuery query) {
        int offset = Math.multiplyExact(query.page() - 1, query.size());
        if (offset >= visibleIds.size()) {
            return new MessagePageView(List.of(), query.page(), query.size(), visibleIds.size());
        }
        int end = Math.min(offset + query.size(), visibleIds.size());
        List<Long> pageIds = visibleIds.subList(offset, end);
        Map<Long, Message> messagesById = loadMessagesById(pageIds);
        Set<Long> withAttachments = listMessageIdsWithAttachments(pageIds);
        List<String> terms = rawTerms(query.queryText());
        List<MessageSummaryView> items = new ArrayList<>(pageIds.size());
        for (Long messageId : pageIds) {
            Message message = messagesById.get(messageId);
            items.add(MailReadApplicationService.toSummary(
                    message,
                    withAttachments.contains(messageId),
                    literalSnippet(message, query.field(), terms)));
        }
        return new MessagePageView(items, query.page(), query.size(), visibleIds.size());
    }

    private List<Long> filterCandidateIds(
            List<Long> candidateIds,
            MailSearchQuery query) {
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        Set<Long> visible = new HashSet<>();
        for (int start = 0; start < candidateIds.size(); start += CANDIDATE_FILTER_BATCH_SIZE) {
            int end = Math.min(start + CANDIDATE_FILTER_BATCH_SIZE, candidateIds.size());
            LambdaQueryWrapper<Message> wrapper = buildAuthorityQuery(query)
                    .in(Message::getId, candidateIds.subList(start, end))
                    .select(Message::getId);
            messageMapper.selectObjs(wrapper)
                    .forEach(id -> visible.add(((Number) id).longValue()));
        }
        return candidateIds.stream().filter(visible::contains).toList();
    }

    private List<Long> filterCandidateIds(
            List<Long> candidateIds,
            MailSearchQuery.RelevanceQuery query) {
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        Set<Long> visible = new HashSet<>();
        for (int start = 0; start < candidateIds.size(); start += CANDIDATE_FILTER_BATCH_SIZE) {
            int end = Math.min(start + CANDIDATE_FILTER_BATCH_SIZE, candidateIds.size());
            LambdaQueryWrapper<Message> wrapper = buildAuthorityQuery(query)
                    .in(Message::getId, candidateIds.subList(start, end))
                    .select(Message::getId);
            messageMapper.selectObjs(wrapper)
                    .forEach(id -> visible.add(((Number) id).longValue()));
        }
        return candidateIds.stream().filter(visible::contains).toList();
    }

    private static LambdaQueryWrapper<Message> buildAuthorityQuery(MailSearchQuery query) {
        return buildAuthorityQuery(
                query.accountId(),
                query.category(),
                query.tagId(),
                query.direction(),
                query.receivedAfter(),
                query.receivedBefore(),
                query.hasAttachment(),
                query.includeSpam());
    }

    private static LambdaQueryWrapper<Message> buildAuthorityQuery(
            MailSearchQuery.RelevanceQuery query) {
        return buildAuthorityQuery(
                query.accountId(),
                query.category(),
                query.tagId(),
                query.direction(),
                query.receivedAfter(),
                query.receivedBefore(),
                query.hasAttachment(),
                query.includeSpam());
    }

    private static LambdaQueryWrapper<Message> buildAuthorityQuery(
            Long accountId,
            MessageCategory category,
            Long tagId,
            MailSearchQuery.DirectionSelection direction,
            java.time.OffsetDateTime receivedAfter,
            java.time.OffsetDateTime receivedBefore,
            Boolean hasAttachment,
            boolean includeSpam) {
        LambdaQueryWrapper<Message> wrapper = Wrappers.lambdaQuery(Message.class)
                .isNull(Message::getDeletedAt)
                .eq(Message::getPurged, false);
        if (accountId != null) {
            wrapper.eq(Message::getMailAccountId, accountId);
        }
        if (category != null) {
            wrapper.eq(Message::getCategory, category);
        }
        if (tagId != null) {
            wrapper.apply("tags @> ARRAY[CAST({0} AS bigint)]", tagId);
        }
        if (direction == MailSearchQuery.DirectionSelection.INBOUND) {
            wrapper.eq(Message::getDirection, MessageDirection.INBOUND);
        } else if (direction == MailSearchQuery.DirectionSelection.OUTBOUND) {
            wrapper.eq(Message::getDirection, MessageDirection.OUTBOUND);
        }
        if (receivedAfter != null) {
            wrapper.ge(Message::getReceivedAt, receivedAfter);
        }
        if (receivedBefore != null) {
            wrapper.le(Message::getReceivedAt, receivedBefore);
        }
        if (hasAttachment != null) {
            String attachmentQuery = "select 1 from attachment attachment_row "
                    + "where attachment_row.message_id_pk = message.id";
            if (hasAttachment) {
                wrapper.exists(attachmentQuery);
            } else {
                wrapper.notExists(attachmentQuery);
            }
        }
        if (!includeSpam) {
            wrapper.and(spam -> spam
                    .isNull(Message::getCategory)
                    .or()
                    .ne(Message::getCategory, MessageCategory.SPAM));
        }
        return wrapper;
    }

    private static void addSenderPrefix(
            LambdaQueryWrapper<Message> wrapper,
            String queryText) {
        String pattern = escapeLikeLiteral(queryText.trim()) + "%";
        wrapper.and(sender -> sender
                .apply("from_address ilike {0} escape '\\'", pattern)
                .or()
                .apply("coalesce(from_display, '') ilike {0} escape '\\'", pattern));
    }

    private Map<Long, Message> loadMessagesById(List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        List<Message> messages = messageMapper.selectList(
                Wrappers.lambdaQuery(Message.class).in(Message::getId, messageIds));
        Map<Long, Message> result = new HashMap<>();
        messages.forEach(message -> result.put(message.getId(), message));
        return result;
    }

    private Set<Long> listMessageIdsWithAttachments(List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return Set.of();
        }
        List<Attachment> attachments = attachmentMapper.selectList(
                Wrappers.lambdaQuery(Attachment.class)
                        .in(Attachment::getMessageIdPk, messageIds)
                        .select(Attachment::getMessageIdPk));
        Set<Long> result = new LinkedHashSet<>();
        attachments.forEach(attachment -> result.add(attachment.getMessageIdPk()));
        return result;
    }

    private static String literalSnippet(
            Message message,
            MailSearchQuery.SearchField field,
            List<String> terms) {
        return switch (field) {
            case SUBJECT -> snippetAround(message.getSubject(), terms);
            case BODY -> snippetAround(message.getBodyText(), terms);
            case ANY -> containsAny(message.getSubject(), terms)
                    ? snippetAround(message.getSubject(), terms)
                    : snippetAround(message.getBodyText(), terms);
            case SENDER -> senderSnippet(message, terms);
        };
    }

    private static String senderSnippet(Message message, List<String> terms) {
        if (containsAny(message.getFromDisplay(), terms)) {
            return truncateByCodePoint(message.getFromDisplay(), SNIPPET_CODE_POINTS);
        }
        return truncateByCodePoint(message.getFromAddress(), SNIPPET_CODE_POINTS);
    }

    private static String matchedField(Message message, List<String> terms) {
        if (containsAny(message.getSubject(), terms)) {
            return "subject";
        }
        if (containsAny(message.getFromDisplay(), terms)) {
            return "fromDisplay";
        }
        return "body";
    }

    private static String snippetAround(String text, List<String> terms) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int matchIndex = -1;
        for (String term : terms) {
            int current = lower.indexOf(term.toLowerCase(Locale.ROOT));
            if (current >= 0 && (matchIndex < 0 || current < matchIndex)) {
                matchIndex = current;
            }
        }
        if (matchIndex < 0) {
            return truncateByCodePoint(text, SNIPPET_CODE_POINTS);
        }
        int totalCodePoints = text.codePointCount(0, text.length());
        int matchCodePoint = text.codePointCount(0, matchIndex);
        int startCodePoint = Math.max(0, matchCodePoint - SNIPPET_CONTEXT_BEFORE);
        int endCodePoint = Math.min(totalCodePoints, startCodePoint + SNIPPET_CODE_POINTS);
        int startOffset = text.offsetByCodePoints(0, startCodePoint);
        int endOffset = text.offsetByCodePoints(0, endCodePoint);
        return text.substring(startOffset, endOffset);
    }

    private static boolean containsAny(String text, List<String> terms) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return terms.stream().anyMatch(term -> lower.contains(term.toLowerCase(Locale.ROOT)));
    }

    private static List<String> rawTerms(String queryText) {
        return List.of(queryText.trim().split("\\s+"));
    }

    private static String truncateByCodePoint(String value, int limit) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int count = value.codePointCount(0, value.length());
        if (count <= limit) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, limit));
    }

    private static String escapeLikeLiteral(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static MessageDirection toDirection(
            MailSearchQuery.DirectionSelection direction) {
        return switch (direction) {
            case INBOUND -> MessageDirection.INBOUND;
            case OUTBOUND -> MessageDirection.OUTBOUND;
            case ALL -> null;
        };
    }

    private static void validateLiteralQuery(MailSearchQuery query) {
        List<ValidationErrorItem> errors = commonValidation(
                query.queryText(),
                query.accountId(),
                query.tagId(),
                query.direction(),
                query.receivedAfter(),
                query.receivedBefore());
        if (query.field() == null) {
            errors.add(new ValidationErrorItem("field", "检索字段不能为空"));
        }
        if (query.order() == null) {
            errors.add(new ValidationErrorItem("order", "排序方向不能为空"));
        }
        if (query.page() < 1) {
            errors.add(new ValidationErrorItem("page", "必须从 1 起"));
        }
        if (query.size() < 1 || query.size() > MAX_PAGE_SIZE) {
            errors.add(new ValidationErrorItem("size", "必须在 1 到 100 之间"));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
    }

    private static void validateRelevanceQuery(MailSearchQuery.RelevanceQuery query) {
        List<ValidationErrorItem> errors = commonValidation(
                query.queryText(),
                query.accountId(),
                query.tagId(),
                query.direction(),
                query.receivedAfter(),
                query.receivedBefore());
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
    }

    private static List<ValidationErrorItem> commonValidation(
            String queryText,
            Long accountId,
            Long tagId,
            MailSearchQuery.DirectionSelection direction,
            java.time.OffsetDateTime receivedAfter,
            java.time.OffsetDateTime receivedBefore) {
        List<ValidationErrorItem> errors = new ArrayList<>();
        if (queryText == null || queryText.isBlank()) {
            errors.add(new ValidationErrorItem("q", "关键词不能为空"));
        }
        if (accountId != null && accountId <= 0) {
            errors.add(new ValidationErrorItem("accountId", "必须为正数"));
        }
        if (tagId != null && tagId <= 0) {
            errors.add(new ValidationErrorItem("tagId", "必须为正数"));
        }
        if (direction == null) {
            errors.add(new ValidationErrorItem("direction", "方向不能为空"));
        }
        if (receivedAfter != null
                && receivedBefore != null
                && receivedAfter.isAfter(receivedBefore)) {
            errors.add(new ValidationErrorItem("receivedAfter", "不能晚于 receivedBefore"));
        }
        return errors;
    }
}
