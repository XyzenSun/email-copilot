package com.xyzensun.emailcopilot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xyzensun.emailcopilot.domain.enums.TurnStatus;
import lombok.Data;
import lombok.ToString;

import java.time.OffsetDateTime;

/**
 * 一个用户请求及其最终回答（{@code DATABASE.md} §5.2）。
 *
 * <p><b>{@code uk (conversationId) WHERE status='running'}</b> 保证同一对话同时只有一轮在跑。
 * 第二次提问由数据库当场拒绝，前端提示"当前对话还有一轮在进行"。
 *
 * <p><b>多轮上下文重建只取 COMPLETED 的 turn</b>，按 {@link #startedAt} 升序拼
 * {@code (userMessage, finalAnswer)} 对。FAILED / CANCELLED 构不成一问一答，不进历史；
 * 中间工具调用栈不持久化因此也不参与重建。
 *
 * <p>不设 {@code deadlineAt}：截止时刻恒为 {@code startedAt + 整轮时长}（全局配置），
 * 属派生值。超时清扫直接判 {@code startedAt < now() - <配置时长>}。
 * 代价是调整护栏时长会立即作用于正在跑的 Turn，单用户自用可接受。
 */
@Data
@TableName("turn")
public class Turn {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 逻辑引用 conversation。 */
    private Long conversationId;

    /** 用户原始输入。<b>进模型前必须包裹为不可信内容。</b> */
    @ToString.Exclude
    private String userMessage;

    /**
     * COMPLETED 时必填（check 约束保证）；CANCELLED 时存停止前已生成的部分文字。
     *
     * <p>取消时保存半截文字是因为用户按停止往往正是想看清 AI 说到哪儿、是不是跑偏了。
     * 但它<b>不参与上下文重建</b>：半截话构不成问答，而且它可能正是用户嫌它跑偏才停的。
     */
    @ToString.Exclude
    private String finalAnswer;

    private TurnStatus status;

    /** FAILED 时必填（check 约束保证）。 */
    private String failureReason;

    /**
     * 护栏计数，默认上限 10 次。
     *
     * <p><b>Spring AI 的工具循环无内置硬上限</b>（spike 结论），
     * 应用层靠 {@code ConversationToolCallingManager} 包装 {@code DefaultToolCallingManager}
     * 在 {@code executeToolCalls} 入口计数自加护栏（阶段10 design §3.2）。
     * 达到上限时不执行工具、回灌「已达上限」ToolResponse 让模型诚实收尾 → COMPLETED。
     */
    private Integer modelCallCount;

    private OffsetDateTime startedAt;

    /** 终态时刻，COMPLETED / FAILED / CANCELLED 共用。 */
    private OffsetDateTime finishedAt;
}
