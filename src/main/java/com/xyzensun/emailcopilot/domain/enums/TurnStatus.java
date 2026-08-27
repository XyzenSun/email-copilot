package com.xyzensun.emailcopilot.domain.enums;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * 一轮对话的状态（{@code DATABASE.md} §5.2）。
 *
 * <pre>
 * RUNNING → COMPLETED | FAILED | CANCELLED
 * </pre>
 *
 * <p><b>{@code uk (conversation_id) WHERE status='running'}</b> 保证同一对话同时只有一轮在跑。
 * 它解决的是多标签页并发提问：两轮同时跑会各自生成 PendingAction（用户分不清哪批对应哪句话），
 * 且下一轮上下文重建时两组问答按时间交错拼接，AI 读到的是错乱的对话记录。
 *
 * <p>{@code CANCELLED} <b>唯一</b>来源是用户点"停止生成"；进程崩溃遗留的 {@code RUNNING}
 * 由清扫任务判 {@code FAILED} 并填 {@code failure_reason}。
 * 取消时把已生成的半截文字写入 {@code final_answer}（用户按停止往往正是想看清 AI 说到哪儿），
 * 但它<b>不参与上下文重建</b>——半截话构不成问答，而且它可能正是用户嫌它跑偏才停的。
 *
 * <p>只有 {@code COMPLETED} 的轮次参与多轮上下文重建。
 */
public enum TurnStatus implements IEnum<String> {

    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String value;

    TurnStatus(String value) {
        this.value = value;
    }

    @Override
    public String getValue() {
        return value;
    }
}
