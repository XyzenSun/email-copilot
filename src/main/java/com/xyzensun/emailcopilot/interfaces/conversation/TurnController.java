package com.xyzensun.emailcopilot.interfaces.conversation;

import com.xyzensun.emailcopilot.application.conversation.TurnApplicationService;
import com.xyzensun.emailcopilot.application.conversation.model.TurnRequest;
import com.xyzensun.emailcopilot.interfaces.conversation.dto.TurnCreateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 对话轮次的 HTTP 入口（design.md §5、§10.4）。
 *
 * <p>{@code POST /api/turns} 返回 {@code text/event-stream}，首事件为 {@code start}。
 * {@code conversationId=null} 时同请求新建对话。
 *
 * <p>{@code POST /turns/{id}/cancel} 立即返回 204，不等待模型调用完成。
 */
@RestController
@RequestMapping("/api/turns")
public class TurnController {

    private final TurnApplicationService turnApplicationService;

    public TurnController(TurnApplicationService turnApplicationService) {
        this.turnApplicationService = turnApplicationService;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter createTurn(@Valid @RequestBody TurnCreateRequest request) {
        TurnRequest turnRequest = new TurnRequest(request.conversationId(), request.userMessage());
        return turnApplicationService.startTurn(turnRequest);
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelTurn(@PathVariable long id) {
        turnApplicationService.cancelTurn(id);
    }
}
