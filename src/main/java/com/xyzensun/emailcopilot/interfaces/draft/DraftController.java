package com.xyzensun.emailcopilot.interfaces.draft;

import com.xyzensun.emailcopilot.application.draft.DraftApplicationService;
import com.xyzensun.emailcopilot.application.draft.DraftPolishService;
import com.xyzensun.emailcopilot.application.draft.model.DraftView;
import com.xyzensun.emailcopilot.domain.Recipients;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.DraftMapper;
import com.xyzensun.emailcopilot.interfaces.draft.dto.DraftCreateRequest;
import com.xyzensun.emailcopilot.interfaces.draft.dto.DraftPageResponse;
import com.xyzensun.emailcopilot.interfaces.draft.dto.DraftResponse;
import com.xyzensun.emailcopilot.interfaces.draft.dto.PolishRequest;
import com.xyzensun.emailcopilot.interfaces.draft.dto.PolishResultResponse;
import com.xyzensun.emailcopilot.interfaces.error.ApiError;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 草稿 CRUD + AI 润色（openapi §11，接口组5）。
 *
 * <p>用户自己保存草稿不经审批。PATCH 的 conversationId/inReplyToMessageId 不可改
 * （DRAFT_ORIGIN_IMMUTABLE）——改了会让"回复哪封"错位。
 */
@RestController
@RequestMapping("/api/drafts")
public class DraftController {

    private final DraftApplicationService draftApplicationService;
    private final DraftPolishService draftPolishService;
    private final DraftMapper draftMapper;

    public DraftController(
            DraftApplicationService draftApplicationService,
            DraftPolishService draftPolishService,
            DraftMapper draftMapper) {
        this.draftApplicationService = draftApplicationService;
        this.draftPolishService = draftPolishService;
        this.draftMapper = draftMapper;
    }

    @GetMapping
    public DraftPageResponse listDrafts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 1) {
            page = 1;
        }
        if (size < 1 || size > 100) {
            size = 20;
        }
        long total = draftMapper.selectCount(null);
        List<DraftView> views = draftApplicationService.listDrafts(page - 1, size);
        return DraftPageResponse.of(page, size, total, views);
    }

    @PostMapping
    public ResponseEntity<DraftResponse> createDraft(@RequestBody DraftCreateRequest request) {
        DraftView view = draftApplicationService.createDraft(
                request.conversationId(),
                request.inReplyToMessageId(),
                request.fromMailAccountId(),
                request.recipients(),
                request.subject(),
                request.bodyText());
        return ResponseEntity.status(HttpStatus.CREATED).body(DraftResponse.from(view));
    }

    @GetMapping("/{id}")
    public DraftResponse getDraft(@PathVariable long id) {
        DraftView view = draftApplicationService.getDraft(id);
        return DraftResponse.from(view);
    }

    @PatchMapping("/{id}")
    public DraftResponse updateDraft(@PathVariable long id, @RequestBody ObjectNode body) {
        // conversationId 与 inReplyToMessageId 不可改（DRAFT_ORIGIN_IMMUTABLE）。
        if (body.has("conversationId") || body.has("inReplyToMessageId")) {
            throw new ApiException(ApiError.DRAFT_ORIGIN_IMMUTABLE);
        }
        Long fromMailAccountId = body.has("fromMailAccountId") && !body.get("fromMailAccountId").isNull()
                ? body.get("fromMailAccountId").asLong() : null;
        Recipients recipients = body.has("recipients") && !body.get("recipients").isNull()
                ? parseRecipients(body.get("recipients")) : null;
        String subject = body.has("subject") && !body.get("subject").isNull()
                ? body.get("subject").asString() : null;
        String bodyText = body.has("bodyText") && !body.get("bodyText").isNull()
                ? body.get("bodyText").asString() : null;
        DraftView view = draftApplicationService.updateDraft(
                id, fromMailAccountId, recipients, subject, bodyText);
        return DraftResponse.from(view);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDraft(@PathVariable long id) {
        draftApplicationService.deleteDraft(id);
    }

    @PostMapping("/polish")
    public PolishResultResponse polish(@RequestBody PolishRequest request) {
        String polished = draftPolishService.polish(request.bodyText(), request.instruction());
        return new PolishResultResponse(polished);
    }

    private static Recipients parseRecipients(tools.jackson.databind.JsonNode node) {
        List<String> to = parseStringList(node.get("to"));
        List<String> cc = parseStringList(node.get("cc"));
        List<String> bcc = parseStringList(node.get("bcc"));
        return new Recipients(to, cc, bcc);
    }

    private static List<String> parseStringList(tools.jackson.databind.JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new java.util.ArrayList<>();
        for (var element : node) {
            result.add(element.asString());
        }
        return result;
    }
}
