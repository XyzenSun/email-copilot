package com.xyzensun.emailcopilot.infrastructure.ai.tools;

import com.xyzensun.emailcopilot.application.conversation.PendingActionProposalService;
import com.xyzensun.emailcopilot.application.conversation.TurnEventSink;
import com.xyzensun.emailcopilot.domain.enums.ActionType;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 本地删除提案工具（design.md §4.3）。
 *
 * <p><b>只创建 PendingAction，不执行删除</b>。target_message_ids 在创建时展开为具体邮件 id，
 * 排序去重后写入。阶段 8 的 approve 端点才执行真实本地删除。
 *
 * <p>对邮箱服务器只读：本地删除只标记 deleted_at，不删服务器邮件。
 */
@Component
public class LocalDeleteProposalTool {

    private final PendingActionProposalService proposalService;

    public LocalDeleteProposalTool(PendingActionProposalService proposalService) {
        this.proposalService = proposalService;
    }

    @Tool(name = "propose_local_delete", description = """
            创建本地删除提案。传入要删除的邮件 id 列表，用户审批后才执行本地删除。
            本地删除只标记 deleted_at，不影响邮箱服务器上的邮件。
            目标邮件必须存在且可见。
            """)
    public String proposeLocalDelete(
            @ToolParam(description = "要删除的邮件 id 列表") List<Long> targetMessageIds,
            ToolContext toolContext) {
        Long turnId = (Long) toolContext.getContext().get("turnId");
        TurnEventSink sink = (TurnEventSink) toolContext.getContext().get("eventSink");

        long proposalId = proposalService.createLocalDeleteProposal(
                turnId, null, targetMessageIds);

        // 前推 SSE action 事件：只给 id + 类型，前端据此 GET /actions/{id} 取详情渲染卡片。
        if (sink != null) {
            sink.sendAction(proposalId, ActionType.LOCAL_DELETE);
        }
        return "已创建本地删除提案 (id=" + proposalId + ")，涉及 "
                + targetMessageIds.size() + " 封邮件。用户审批后才执行删除。";
    }
}
