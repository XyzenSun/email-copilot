package com.xyzensun.emailcopilot.application.conversation;

import com.xyzensun.emailcopilot.domain.enums.ActionType;
import com.xyzensun.emailcopilot.domain.enums.EvidenceSource;
import com.xyzensun.emailcopilot.domain.enums.EvidenceTargetType;

import java.time.OffsetDateTime;

/**
 * Turn 执行过程中向 SSE 前推事件的出口（design.md §5、§3.4）。
 *
 * <p>evidence / action 事件由代码产生，不由模型自述（DECISIONS §6、openapi SseEvidenceEvent）。
 * 工具执行时经 {@code ToolContext} 取得本接口实例，紧挨着写 DB 的调用前推事件；
 * {@link TurnApplicationService} 的 {@link TurnEventSinkImpl} 实现把事件写入 SSE 流。
 *
 * <p><b>evidence 事件携带 openapi {@code ReadEvidence} 的全部 6 个字段</b>
 * （targetType/targetId/source + subject/fromAddress/receivedAt）：前端直接用事件 payload
 * 渲染证据卡片，{@code subject} 为 null 时前端显示「该邮件已删除」——因此对存在的邮件必须
 * 带真实 subject。这些富化字段由工具传入（工具此时已持有这些数据：search 已加载全 Message、
 * read_message 已有 MessageSummaryView、read_thread 有会话内邮件列表），SSE 出口不重复查库。
 *
 * <p>action 事件只给 id + 类型（openapi SseActionEvent），前端据此调 {@code GET /actions/{id}}
 * 取结构化详情渲染卡片。
 *
 * <p>targetType / source / actionType 用领域枚举传入，由实现经 {@code .getValue()} 序列化为
 * openapi 的小写串（{@code message}/{@code relevance_search}/{@code save_draft}），
 * 避免调用方手写字符串大小写写错。
 */
public interface TurnEventSink {

    /** 前推一个 token 文本片段（event: token data {text}）。 */
    void sendToken(String text);

    /** 前推一个读取证据事件（event: evidence，对齐 openapi ReadEvidence 6 字段）。 */
    void sendEvidence(EvidenceTargetType targetType, long targetId, EvidenceSource source,
                      String subject, String fromAddress, OffsetDateTime receivedAt);

    /** 前推一个提案动作事件（event: action data {pendingActionId, actionType}）。 */
    void sendAction(long pendingActionId, ActionType actionType);

    /** 前推一个草稿创建事件（event: draft data {draftId, subject, toPreview}）。
     *  草稿免审批直建草稿箱（2026-08-25），无 PendingAction 故不走 action 事件。
     *  subject/toPreview 由 SaveDraftTool 建草稿时已持有，零额外查询。 */
    void sendDraft(long draftId, String subject, String toPreview);

    /** 前推一个压缩事件（event: compacted data {compactedTurnCount, usedTokens}）。 */
    void sendCompacted(int compactedTurnCount, int usedTokens);

    /** 前推一个错误事件（event: error data {status, code, title}）。 */
    void sendError(int status, String code, String title);

    /** 是否已取消（取消后 sendXxx 静默丢弃，避免往已 complete 的 emitter 发数据）。 */
    boolean isCancelled();
}
