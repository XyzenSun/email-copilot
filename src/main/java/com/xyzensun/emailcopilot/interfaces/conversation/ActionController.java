package com.xyzensun.emailcopilot.interfaces.conversation;

import com.xyzensun.emailcopilot.application.approval.ApprovalApplicationService;
import com.xyzensun.emailcopilot.application.approval.model.ApprovalResultView;
import com.xyzensun.emailcopilot.application.conversation.PendingActionQueryService;
import com.xyzensun.emailcopilot.application.conversation.model.PendingActionDetailView;
import com.xyzensun.emailcopilot.application.conversation.model.PendingActionSummaryView;
import com.xyzensun.emailcopilot.interfaces.conversation.dto.ApprovalResultResponse;
import com.xyzensun.emailcopilot.interfaces.conversation.dto.PendingActionDetailResponse;
import com.xyzensun.emailcopilot.interfaces.conversation.dto.PendingActionListResponse;
import com.xyzensun.emailcopilot.interfaces.conversation.dto.PendingActionSummaryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 待审批提案列表、详情与审批执行。
 *
 * <p><b>approve/reject</b>（阶段8）：approve 同步返回执行终态（200+status），
 * reject 不带请求体、execution 恒 null。CAS 零行 → 409。
 */
@RestController
@RequestMapping("/api/actions")
public class ActionController {

    private final PendingActionQueryService pendingActionQueryService;
    private final ApprovalApplicationService approvalApplicationService;

    public ActionController(
            PendingActionQueryService pendingActionQueryService,
            ApprovalApplicationService approvalApplicationService) {
        this.pendingActionQueryService = pendingActionQueryService;
        this.approvalApplicationService = approvalApplicationService;
    }

    @GetMapping
    public PendingActionListResponse listPendingActions() {
        List<PendingActionSummaryView> views = pendingActionQueryService.listPendingActions();
        List<PendingActionSummaryResponse> items = views.stream()
                .map(PendingActionSummaryResponse::from)
                .toList();
        return new PendingActionListResponse(items);
    }

    @GetMapping("/{id}")
    public PendingActionDetailResponse getPendingAction(@PathVariable long id) {
        PendingActionDetailView view = pendingActionQueryService.getPendingAction(id);
        return PendingActionDetailResponse.from(view);
    }

    @PostMapping("/{id}/approve")
    public ApprovalResultResponse approve(@PathVariable long id) {
        ApprovalResultView view = approvalApplicationService.approve(id);
        return ApprovalResultResponse.from(view);
    }

    @PostMapping("/{id}/reject")
    public ApprovalResultResponse reject(@PathVariable long id) {
        ApprovalResultView view = approvalApplicationService.reject(id);
        return ApprovalResultResponse.from(view);
    }
}
