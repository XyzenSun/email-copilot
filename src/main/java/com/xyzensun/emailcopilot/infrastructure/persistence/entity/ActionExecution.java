package com.xyzensun.emailcopilot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xyzensun.emailcopilot.domain.enums.ExecutionStatus;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 一次已批准操作<b>唯一</b>的执行尝试（{@code DATABASE.md} §5.6）。
 *
 * <p><b>{@link #pendingActionId} 作主键是核心不变式</b>：一次批准只可能有一次执行，
 * 从结构上排除"一次批准被放大成多次副作用"。不得自动开启第二次执行。
 *
 * <p>批准与创建本行（status=EXECUTING）<b>必须在同一数据库事务内</b>，
 * 用条件更新原子消费 {@code pending_action.approvalStatus}：
 *
 * <pre>{@code
 * update pending_action
 * set approval_status = 'approved', decided_at = now()
 * where id = ? and approval_status = 'pending' and expires_at > now()
 * returning id;
 * }</pre>
 *
 * 返回一行则同事务 INSERT 本表；返回零行表示已决定/已过期/已消费，不重复执行。
 *
 * <p><b>发信顺序不能变</b>（{@code ARCHITECTURE.md} §6.2）：
 * 短事务原子消费批准 → <b>事务外</b>提交 SMTP → 新事务记结果。
 * 这个顺序不能消除 SMTP 最终响应丢失造成的不确定性，
 * 但能保证同一批准不会被两个执行者同时使用。
 */
@Data
@TableName("action_execution")
public class ActionExecution {

    /** 逻辑引用 pending_action，1:1，由应用指定，不自增。 */
    @TableId(type = IdType.INPUT)
    private Long pendingActionId;

    private ExecutionStatus status;

    private OffsetDateTime startedAt;

    private OffsetDateTime finishedAt;

    /**
     * SMTP 最终响应原文或本地错误描述，界面直接显示。
     *
     * <p>单列文本不拆 {@code resultCode} + JSONB 详情：SMTP 最终响应本身就是一行文本
     * （{@code 550 5.1.1 <x@y>: Recipient address rejected}），
     * 用 JSONB 装一行文本是过度结构化，界面还得从 JSON 里挖字段。
     */
    private String resultMessage;
}
