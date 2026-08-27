package com.xyzensun.emailcopilot.interfaces.draft;

import com.xyzensun.emailcopilot.application.send.SendApplicationService;
import com.xyzensun.emailcopilot.application.send.model.SendResultView;
import com.xyzensun.emailcopilot.interfaces.draft.dto.SendRequest;
import com.xyzensun.emailcopilot.interfaces.draft.dto.SendResultResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户直接发信（openapi §11，{@code POST /send}）。
 *
 * <p>不经审批——背后的发信服务从不注册为 AI 工具。
 * 三种结果全部返回 HTTP 200，读 status 分支。
 */
@RestController
@RequestMapping("/api")
public class SendController {

    private final SendApplicationService sendApplicationService;

    public SendController(SendApplicationService sendApplicationService) {
        this.sendApplicationService = sendApplicationService;
    }

    @PostMapping("/send")
    public SendResultResponse send(@RequestBody SendRequest request) {
        SendResultView view = sendApplicationService.send(
                request.fromMailAccountId(),
                request.inReplyToMessageId(),
                request.recipients(),
                request.subject(),
                request.bodyText(),
                request.draftId());
        return new SendResultResponse(view.status(), view.messageId(), view.resultMessage());
    }
}
