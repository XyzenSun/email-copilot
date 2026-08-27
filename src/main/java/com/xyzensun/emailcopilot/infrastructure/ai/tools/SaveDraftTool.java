package com.xyzensun.emailcopilot.infrastructure.ai.tools;

import com.xyzensun.emailcopilot.application.conversation.TurnEventSink;
import com.xyzensun.emailcopilot.application.draft.DraftApplicationService;
import com.xyzensun.emailcopilot.application.draft.model.DraftView;
import com.xyzensun.emailcopilot.domain.Recipients;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Turn;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.TurnMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 草稿创建工具（2026-08-25 起免审批直建草稿）。
 *
 * <p><b>设计变更</b>：原 {@code propose_save_draft} 走 PendingAction 审批链路（创建提案 →
 * 用户审批 → 执行链写 draft 表）。现改为直接调用 {@link DraftApplicationService#createDraft}
 * 建草稿进草稿箱——因为草稿进草稿箱本身<b>可逆</b>（草稿可删可改、未发送），用户在草稿箱
 * 手动 {@code POST /send} 发送才是不可逆动作，"手动发送"即审批环节，故建草稿本身无需审批。
 * 剩 {@code propose_local_delete}（删除不可逆）仍需审批。
 *
 * <p>原审批链路代码（{@code createSaveDraftProposal} / {@code approveSaveDraft} /
 * {@code ActionType.SAVE_DRAFT}）保留不删——与 {@code propose_send_email} 暂时关闭同等处理，
 * 避免 DB 枚举 migration，且 send_email 未来恢复审批时复用 ContentSnapshot 机制。
 *
 * <p>不前推 SSE action 事件（无 PendingAction 了）。工具返回告知 AI 草稿已建好 + id，
 * AI 在回答里转述用户去草稿箱查看发送。
 *
 * <p>参数经 {@code createDraft} 重新业务校验（账号存在 / 回复邮件可见 / 收件人地址合法），
 * 校验失败抛出的异常会被 ToolCallingManager 当作工具失败回灌模型。
 */
@Component
public class SaveDraftTool {

    private final DraftApplicationService draftApplicationService;
    private final TurnMapper turnMapper;

    public SaveDraftTool(DraftApplicationService draftApplicationService, TurnMapper turnMapper) {
        this.draftApplicationService = draftApplicationService;
        this.turnMapper = turnMapper;
    }

    @Tool(name = "save_draft", description = """
            创建一份邮件草稿并保存到草稿箱。草稿进草稿箱后由用户在草稿箱手动发送——
            手动发送是不可逆动作，即审批环节，故建草稿本身无需审批。
            收件人地址、发信账号、主题和正文都会被校验，失败会回灌错误信息。返回草稿 id。
            """)
    public String saveDraft(
            @ToolParam(description = "发信邮箱账号 id") Long fromMailAccountId,
            @ToolParam(description = "可选：收件人列表", required = false) List<String> to,
            @ToolParam(description = "可选：抄送列表", required = false) List<String> cc,
            @ToolParam(description = "可选：密送列表", required = false) List<String> bcc,
            @ToolParam(description = "可选：回复的邮件 id", required = false) Long inReplyToMessageId,
            @ToolParam(description = "邮件主题，允许空字符串") String subject,
            @ToolParam(description = "邮件纯文本正文，允许空字符串") String bodyText,
            ToolContext toolContext) {
        Long turnId = (Long) toolContext.getContext().get("turnId");
        TurnEventSink sink = (TurnEventSink) toolContext.getContext().get("eventSink");
        Long conversationId = resolveConversationId(turnId);

        Recipients recipients = new Recipients(
                to != null ? to : List.of(),
                cc != null ? cc : List.of(),
                bcc != null ? bcc : List.of());

        DraftView draft = draftApplicationService.createDraft(
                conversationId,
                inReplyToMessageId,
                fromMailAccountId,
                recipients,
                subject != null ? subject : "",
                bodyText != null ? bodyText : "");

        // 前推 SSE draft 事件：草稿免审批无 PendingAction，不走 action 事件。
        // subject/toPreview 工具已持有（来自 AI 工具参数），零额外查询。前端据此渲染"草稿已创建"提示卡。
        if (sink != null) {
            sink.sendDraft(draft.id(),
                    subject != null ? subject : "",
                    recipients.to().isEmpty() ? null : String.join(", ", recipients.to()));
        }

        return "已创建草稿（id=" + draft.id() + "）到草稿箱。用户可在草稿箱查看并手动发送。";
    }

    /** turnId → conversationId（草稿需关联对话；turn 不存在时为 null，即孤立草稿）。 */
    private Long resolveConversationId(Long turnId) {
        if (turnId == null) {
            return null;
        }
        Turn turn = turnMapper.selectById(turnId);
        return turn != null ? turn.getConversationId() : null;
    }
}
