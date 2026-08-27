package com.xyzensun.emailcopilot.domain.enums;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * 一次已批准操作的执行结果（{@code DATABASE.md} §5.6）。
 *
 * <pre>
 * EXECUTING → SUCCEEDED | FAILED | INDETERMINATE
 * </pre>
 *
 * <p>{@code action_execution} 用 {@code pending_action_id} 作主键，这是核心不变式：
 * <b>一次批准只可能有一次执行</b>，从结构上排除"一次批准被放大成多次副作用"。
 * 不得自动开启第二次执行。
 *
 * <p><b>{@code INDETERMINATE} 是这里最要紧的一个值</b>：完整邮件已提交给 SMTP 但最终响应丢失。
 * 此时那封信很可能已经发出去了，而按已定取舍<b>结果不确定的邮件不入库</b>
 * （{@code DATABASE.md} §3.2.1）——系统里不留任何持久记录，刷新页面后无法再判断它到底发没发。
 *
 * <p>三条随之而来的硬性要求：
 * <ul>
 *   <li><b>绝不自动重发</b></li>
 *   <li>接口返回 <b>HTTP 200 + status</b>，不是 4xx/5xx——批准是一次一用、已被消费，
 *       返回错误码会让前端以为没生效而重试</li>
 *   <li>前端<b>绝不清空编辑框</b>，草稿里那份文字是这封信唯一的痕迹</li>
 * </ul>
 *
 * <p>执行失联且不能证明副作用尚未开始时，保守记为 {@code INDETERMINATE}。
 */
public enum ExecutionStatus implements IEnum<String> {

    EXECUTING("executing"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    INDETERMINATE("indeterminate");

    private final String value;

    ExecutionStatus(String value) {
        this.value = value;
    }

    @Override
    public String getValue() {
        return value;
    }
}
