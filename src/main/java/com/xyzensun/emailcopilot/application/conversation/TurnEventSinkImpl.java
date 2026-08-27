package com.xyzensun.emailcopilot.application.conversation;

import com.xyzensun.emailcopilot.domain.enums.ActionType;
import com.xyzensun.emailcopilot.domain.enums.EvidenceSource;
import com.xyzensun.emailcopilot.domain.enums.EvidenceTargetType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@link TurnEventSink} 的请求级实现（design.md §3.4、research §3.1）。
 *
 * <p>包住一个 {@link SseEmitter} + turnId + 取消集合，是纯事件出口——<b>不查库、不富化</b>。
 * evidence 的 subject/fromAddress/receivedAt 由工具（此时已持有这些数据）经参数传入，
 * 避免在 SSE 出口重复查邮件：search 已 {@code loadMessagesById} 加载全 Message、
 * read_message 已有 {@code MessageSummaryView}、read_thread 有会话内邮件列表。
 *
 * <p><b>取消语义</b>：{@link #isCancelled()} 为真时所有 sendXxx 静默丢弃，避免取消后往已
 * complete 的 emitter 发数据触发 IOException 噪声（research caveat）。
 *
 * <p><b>IOException 吞掉</b>：{@link #emit} 捕获 IOException 仅记 debug 日志——SSE 客户端
 * 断连等不应让整轮 turn 崩溃（与原 driveStream/handleError 内联发送的容错一致）。
 *
 * <p>evidence 的 data 用 {@link LinkedHashMap} 保序，使字段顺序与 openapi 示例一致
 * （便于前端阅读与排错）；且 LinkedHashMap 允许 null 值（subject/fromAddress/receivedAt
 * 对已删除目标/异常路径可为 null），{@code Map.of} 不允许 null 故此处不能用。
 */
class TurnEventSinkImpl implements TurnEventSink {

    private static final Logger log = LoggerFactory.getLogger(TurnEventSinkImpl.class);

    private final SseEmitter emitter;
    private final long turnId;
    private final Set<Long> cancelledTurnIds;

    TurnEventSinkImpl(SseEmitter emitter, long turnId, Set<Long> cancelledTurnIds) {
        this.emitter = emitter;
        this.turnId = turnId;
        this.cancelledTurnIds = cancelledTurnIds;
    }

    @Override
    public void sendToken(String text) {
        emit("token", Map.of("text", text));
    }

    @Override
    public void sendEvidence(EvidenceTargetType targetType, long targetId, EvidenceSource source,
                            String subject, String fromAddress, OffsetDateTime receivedAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("targetType", targetType.getValue());
        data.put("targetId", targetId);
        data.put("source", source.getValue());
        data.put("subject", subject);
        data.put("fromAddress", fromAddress);
        data.put("receivedAt", receivedAt);
        emit("evidence", data);
    }

    @Override
    public void sendAction(long pendingActionId, ActionType actionType) {
        emit("action", Map.of(
                "pendingActionId", pendingActionId,
                "actionType", actionType.getValue()));
    }

    @Override
    public void sendDraft(long draftId, String subject, String toPreview) {
        emit("draft", Map.of(
                "draftId", draftId,
                "subject", subject,
                "toPreview", toPreview));
    }

    @Override
    public void sendCompacted(int compactedTurnCount, int usedTokens) {
        emit("compacted", Map.of(
                "compactedTurnCount", compactedTurnCount,
                "usedTokens", usedTokens));
    }

    @Override
    public void sendError(int status, String code, String title) {
        emit("error", Map.of(
                "status", status,
                "code", code,
                "title", title));
    }

    @Override
    public boolean isCancelled() {
        return cancelledTurnIds.contains(turnId);
    }

    private void emit(String name, Object data) {
        if (isCancelled()) {
            return;
        }
        try {
            sendEvent(name, data);
        } catch (IOException exception) {
            log.debug("发送 SSE {} 事件失败（turnId={}）", name, turnId, exception);
        }
    }

    /**
     * 把单个事件写入 SSE 流。生产用 {@link SseEmitter}；测试可覆写以捕获事件名 + data，
     * 免去与 {@code SseEventBuilder} 内部结构（私有 StringBuilder）耦合——
     * Spring 的 SSE builder 把 event 名拼进私有 sb，从外部无法可靠提取，故留此缝。
     */
    void sendEvent(String name, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(data));
    }
}
