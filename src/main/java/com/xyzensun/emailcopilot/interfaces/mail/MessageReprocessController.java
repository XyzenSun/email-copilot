package com.xyzensun.emailcopilot.interfaces.mail;

import com.xyzensun.emailcopilot.application.processing.ManualReprocessApplicationService;
import com.xyzensun.emailcopilot.application.processing.ReprocessStage;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import com.xyzensun.emailcopilot.interfaces.error.ValidationErrorItem;
import com.xyzensun.emailcopilot.interfaces.mail.dto.ReprocessRequest;
import com.xyzensun.emailcopilot.interfaces.mail.dto.ReprocessResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 单封手动重新处理入口（阶段12）。与 {@link MailReadController} 分离：后者自称「最小邮件可见性
 * 入口」，「触发流水线某步」是动作而非可见性查询，放独立 Controller 不污染只读语义。
 *
 * <p>请求体 {@code stage} 取四值之一（{@code spam_judgment}/{@code classification}/
 * {@code translation}/{@code summary}）；非法值（含 {@code sender_rule}/
 * {@code language_detection}/{@code done}）由 {@link #parseStage} 落 400
 * {@code VALIDATION_FAILED}（design.md §3.1）。其余业务错误由
 * {@link ManualReprocessApplicationService} 抛 {@link ApiException}。
 */
@RestController
public class MessageReprocessController {

    private final ManualReprocessApplicationService manualReprocessApplicationService;

    public MessageReprocessController(ManualReprocessApplicationService manualReprocessApplicationService) {
        this.manualReprocessApplicationService = manualReprocessApplicationService;
    }

    @PostMapping("/api/messages/{id}/reprocess")
    public ReprocessResponse reprocess(@PathVariable long id, @Valid @RequestBody ReprocessRequest request) {
        ReprocessStage stage = parseStage(request.stage());
        return manualReprocessApplicationService.reprocess(id, stage);
    }

    private static ReprocessStage parseStage(String value) {
        ReprocessStage stage = ReprocessStage.fromValue(value);
        if (stage == null) {
            throw ApiException.validationFailed(List.of(
                    new ValidationErrorItem("stage", "只支持垃圾评分、分类与标签、翻译或摘要")));
        }
        return stage;
    }
}
