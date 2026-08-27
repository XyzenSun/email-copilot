package com.xyzensun.emailcopilot.interfaces.conversation;

import com.xyzensun.emailcopilot.application.conversation.ConversationApplicationService;
import com.xyzensun.emailcopilot.application.conversation.model.ConversationDetailView;
import com.xyzensun.emailcopilot.application.conversation.model.ConversationSummaryView;
import com.xyzensun.emailcopilot.application.conversation.model.TurnView;
import com.xyzensun.emailcopilot.interfaces.conversation.dto.ConversationDetailResponse;
import com.xyzensun.emailcopilot.interfaces.conversation.dto.ConversationListResponse;
import com.xyzensun.emailcopilot.interfaces.conversation.dto.ConversationPatchRequest;
import com.xyzensun.emailcopilot.interfaces.conversation.dto.ConversationSummaryResponse;
import com.xyzensun.emailcopilot.interfaces.conversation.dto.DeleteConversationResponse;
import com.xyzensun.emailcopilot.interfaces.conversation.dto.TurnResponse;
import org.springframework.http.HttpStatus;
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

import java.util.List;

/**
 * 对话 CRUD 与上下文管理的 HTTP 入口（design.md §10.4，API.md §12）。
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationApplicationService conversationService;

    public ConversationController(ConversationApplicationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping
    public ConversationListResponse listConversations(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean archived) {
        List<ConversationSummaryView> views = conversationService.listConversations(page, size, archived);
        List<ConversationSummaryResponse> items = views.stream()
                .map(ConversationSummaryResponse::from)
                .toList();
        return new ConversationListResponse(items);
    }

    @GetMapping("/{id}")
    public ConversationDetailResponse getConversation(@PathVariable long id) {
        ConversationDetailView view = conversationService.getConversation(id);
        List<TurnResponse> turns = view.turns().stream()
                .map(TurnResponse::from)
                .toList();
        return ConversationDetailResponse.from(view, turns);
    }

    @PatchMapping("/{id}")
    public ConversationDetailResponse patchConversation(
            @PathVariable long id, @RequestBody ConversationPatchRequest request) {
        ConversationDetailView view = conversationService.patchConversation(
                id, request.title(), request.archived());
        List<TurnResponse> turns = view.turns().stream()
                .map(TurnResponse::from)
                .toList();
        return ConversationDetailResponse.from(view, turns);
    }

    @DeleteMapping("/{id}")
    public DeleteConversationResponse deleteConversation(@PathVariable long id) {
        conversationService.deleteConversation(id);
        return new DeleteConversationResponse(id);
    }

    @PostMapping("/{id}/context/clear")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearContext(@PathVariable long id) {
        conversationService.clearContext(id);
    }
}
