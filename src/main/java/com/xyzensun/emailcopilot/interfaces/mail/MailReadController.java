package com.xyzensun.emailcopilot.interfaces.mail;

import com.xyzensun.emailcopilot.application.mail.MailReadApplicationService;
import com.xyzensun.emailcopilot.application.mail.MailSearchService;
import com.xyzensun.emailcopilot.application.mail.MessageDeletionApplicationService;
import com.xyzensun.emailcopilot.application.mail.model.BatchDeleteResult;
import com.xyzensun.emailcopilot.application.mail.model.MailSearchQuery;
import com.xyzensun.emailcopilot.application.mail.model.MessageListQuery;
import com.xyzensun.emailcopilot.domain.enums.MessageCategory;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import com.xyzensun.emailcopilot.interfaces.error.ValidationErrorItem;
import com.xyzensun.emailcopilot.interfaces.mail.dto.BatchDeleteRequest;
import com.xyzensun.emailcopilot.interfaces.mail.dto.BatchDeleteResultResponse;
import com.xyzensun.emailcopilot.interfaces.mail.dto.MessageDetailResponse;
import com.xyzensun.emailcopilot.interfaces.mail.dto.MessagePageResponse;
import com.xyzensun.emailcopilot.interfaces.mail.dto.ThreadDetailResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.List;

/** 阶段 4A 最小邮件可见性入口；Controller 只负责契约字面值解析和响应映射。 */
@RestController
public class MailReadController {

    private final MailReadApplicationService mailReadApplicationService;
    private final MailSearchService mailSearchService;
    private final MessageDeletionApplicationService messageDeletionApplicationService;

    public MailReadController(
            MailReadApplicationService mailReadApplicationService,
            MailSearchService mailSearchService,
            MessageDeletionApplicationService messageDeletionApplicationService) {
        this.mailReadApplicationService = mailReadApplicationService;
        this.mailSearchService = mailSearchService;
        this.messageDeletionApplicationService = messageDeletionApplicationService;
    }

    @GetMapping("/api/messages")
    public MessagePageResponse listMessages(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Long tagId,
            @RequestParam(defaultValue = "inbound") String direction,
            @RequestParam(required = false) OffsetDateTime receivedAfter,
            @RequestParam(required = false) OffsetDateTime receivedBefore,
            @RequestParam(defaultValue = "false") boolean includeSpam) {
        MessageListQuery query = new MessageListQuery(
                page,
                size,
                accountId,
                parseCategory(category),
                tagId,
                parseDirection(direction),
                receivedAfter,
                receivedBefore,
                includeSpam);
        return MessagePageResponse.from(mailReadApplicationService.listMessages(query));
    }

    @GetMapping("/api/search")
    public MessagePageResponse searchMessages(
            @RequestParam String q,
            @RequestParam(defaultValue = "any") String field,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(defaultValue = "all") String direction,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Long tagId,
            @RequestParam(required = false) OffsetDateTime receivedAfter,
            @RequestParam(required = false) OffsetDateTime receivedBefore,
            @RequestParam(required = false) Boolean hasAttachment,
            @RequestParam(defaultValue = "false") boolean includeSpam) {
        return MessagePageResponse.from(mailSearchService.literalSearch(new MailSearchQuery(
                q,
                parseSearchField(field),
                parseSortOrder(order),
                accountId,
                parseCategory(category),
                tagId,
                parseSearchDirection(direction),
                receivedAfter,
                receivedBefore,
                hasAttachment,
                includeSpam,
                page,
                size)));
    }

    @GetMapping("/api/messages/{id}")
    public MessageDetailResponse getMessage(@PathVariable long id) {
        return MessageDetailResponse.from(mailReadApplicationService.getMessage(id));
    }

    /**
     * 软删单封邮件（阶段11 方案 C）：同步清正文 + 留骨架防复活。
     * 已删 → 409 MESSAGE_ALREADY_DELETED；不存在 → 404 MESSAGE_NOT_FOUND。
     */
    @DeleteMapping("/api/messages/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMessage(@PathVariable long id) {
        messageDeletionApplicationService.deleteMessage(id);
    }

    /**
     * 批量软删（宽松语义）：能删都删，已删/不存在不阻止整批，返回三类计数。
     */
    @PostMapping("/api/messages/batch-delete")
    public BatchDeleteResultResponse batchDeleteMessages(@RequestBody BatchDeleteRequest request) {
        BatchDeleteResult result = messageDeletionApplicationService.batchDelete(request.ids());
        return BatchDeleteResultResponse.from(result);
    }

    @GetMapping("/api/threads/{id}")
    public ThreadDetailResponse getThread(@PathVariable long id) {
        return ThreadDetailResponse.from(mailReadApplicationService.getThread(id));
    }

    private static MessageCategory parseCategory(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "primary" -> MessageCategory.PRIMARY;
            case "transaction" -> MessageCategory.TRANSACTION;
            case "promotion" -> MessageCategory.PROMOTION;
            case "social" -> MessageCategory.SOCIAL;
            case "update" -> MessageCategory.UPDATE;
            case "spam" -> MessageCategory.SPAM;
            default -> throw invalidEnum("category", "只支持六个固定邮件分类");
        };
    }

    private static MessageListQuery.DirectionSelection parseDirection(String value) {
        return switch (value) {
            case "inbound" -> MessageListQuery.DirectionSelection.INBOUND;
            case "outbound" -> MessageListQuery.DirectionSelection.OUTBOUND;
            case "all" -> MessageListQuery.DirectionSelection.ALL;
            default -> throw invalidEnum(
                    "direction", "只支持 inbound、outbound 或 all");
        };
    }

    private static MailSearchQuery.SearchField parseSearchField(String value) {
        return switch (value) {
            case "any" -> MailSearchQuery.SearchField.ANY;
            case "body" -> MailSearchQuery.SearchField.BODY;
            case "subject" -> MailSearchQuery.SearchField.SUBJECT;
            case "sender" -> MailSearchQuery.SearchField.SENDER;
            default -> throw invalidEnum(
                    "field", "只支持 any、body、subject 或 sender");
        };
    }

    private static MailSearchQuery.SortOrder parseSortOrder(String value) {
        return switch (value) {
            case "desc" -> MailSearchQuery.SortOrder.DESC;
            case "asc" -> MailSearchQuery.SortOrder.ASC;
            default -> throw invalidEnum("order", "只支持 desc 或 asc");
        };
    }

    private static MailSearchQuery.DirectionSelection parseSearchDirection(String value) {
        return switch (value) {
            case "inbound" -> MailSearchQuery.DirectionSelection.INBOUND;
            case "outbound" -> MailSearchQuery.DirectionSelection.OUTBOUND;
            case "all" -> MailSearchQuery.DirectionSelection.ALL;
            default -> throw invalidEnum(
                    "direction", "只支持 inbound、outbound 或 all");
        };
    }

    private static ApiException invalidEnum(String field, String message) {
        return ApiException.validationFailed(List.of(new ValidationErrorItem(field, message)));
    }
}
