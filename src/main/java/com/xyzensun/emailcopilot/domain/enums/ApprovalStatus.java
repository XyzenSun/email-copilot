package com.xyzensun.emailcopilot.domain.enums;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * 提案的审批状态（{@code DATABASE.md} §5.4）。
 *
 * <pre>
 * PENDING → APPROVED | REJECTED | EXPIRED | CANCELLED
 * </pre>
 *
 * <p>{@code APPROVED} 表示授权<b>已被一次性消费</b>，是审批维度终态。
 * 批准用条件更新原子消费，已决定或已过期时返回零行 → 映射为 409，不重复执行。
 *
 * <p><b>{@code REJECTED} 与 {@code CANCELLED} 的区别</b>：{@code REJECTED} 是用户明确说不要；
 * {@code CANCELLED} 是系统发现提案已无法执行（目标邮件已被手动删除、发信账号已被移除、
 * 内容快照缺失、所属对话已被删除），此时 {@code cancel_reason} 必填（check 约束保证）。
 *
 * <p>不设 {@code decided_by}：决定者可从状态直接推出——{@code APPROVED}/{@code REJECTED}
 * 只可能由用户写入，{@code EXPIRED} 只可能由清扫任务写入，{@code CANCELLED} 的来源见
 * {@code cancel_reason}。
 *
 * <p>Turn 失败或取消<b>不会</b>改变已有提案——提案独立于 Turn 存续，不批准就没有副作用。
 */
public enum ApprovalStatus implements IEnum<String> {

    PENDING("pending"),
    APPROVED("approved"),
    REJECTED("rejected"),
    EXPIRED("expired"),
    CANCELLED("cancelled");

    private final String value;

    ApprovalStatus(String value) {
        this.value = value;
    }

    @Override
    public String getValue() {
        return value;
    }
}
