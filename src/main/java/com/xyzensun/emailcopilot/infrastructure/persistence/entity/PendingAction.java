package com.xyzensun.emailcopilot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xyzensun.emailcopilot.domain.enums.ActionType;
import com.xyzensun.emailcopilot.domain.enums.ApprovalStatus;
import com.xyzensun.emailcopilot.infrastructure.persistence.handler.LongListArrayTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 待审批提案（{@code DATABASE.md} §5.4）。审批状态与执行状态分离，<b>独立于 Turn 存续</b>——
 * Turn 失败或取消不撤销此行，不批准就没有副作用。
 *
 * <p><b>不存 summary（提案概述），审批卡片的文字一律由代码渲染。</b>
 * 这是硬性安全约束：若概述由 AI 生成，注入内容可以诱导它把"删除全部邮件"的提案写成
 * "给张三的邮件加标签"，用户扫一眼标题点批准，闸门等于不存在——
 * 用户审的是 AI 写的一句话，不是它真要做的事。卡片文字必须由 {@link #actionType}
 * （固定枚举）、{@link #targetMessageIds}（明确的 id 列表）、
 * {@link PendingActionContent}（不可变快照）渲染，这些都是 AI 篡改不了的事实。
 *
 * <p><b>两条幂等键必须并行启用</b>（{@code DATABASE.md} §6.1）：
 * 主键 {@code (turnId, providerToolCallId)}，兜底键
 * {@code (turnId, actionType, canonicalPayloadHash)}。spike 实测模型重试时会产生
 * <b>新的</b> tool call ID 不复用旧的，只有主键会漏掉重复提案——
 * 表现为用户批准一次却发出两封相同的邮件。两条键都以 turnId 打头，
 * 保证跨 Turn 不被合并（跨 Turn 的相同提案是新的用户决策）。
 */
@Data
@TableName(value = "pending_action", autoResultMap = true)
public class PendingAction {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 逻辑引用 turn，仅作来源追溯。 */
    private Long turnId;

    private ActionType actionType;

    private ApprovalStatus approvalStatus;

    /**
     * LOCAL_DELETE 的目标邮件 id，逻辑引用 message.id。<b>排序去重后写入。</b>
     *
     * <p>用数组列不建关系表：目标集合创建后<b>永不改动</b>，是纯只读数组。
     *
     * <p>会话级删除在<b>创建提案时</b>展开为具体邮件 id 存入本列，卡片按会话分组展示；
     * 不允许存"会话 id"让执行时再展开，否则批准后新到的回复会落入影响面。
     *
     * <p><b>不设批量上限</b>：单用户自用、用户自批，卡片把目标数量做成醒目提示
     * （"将删除 3000 封邮件"）由用户自己把关。
     */
    @TableField(typeHandler = LongListArrayTypeHandler.class)
    private List<Long> targetMessageIds;

    /**
     * 框架工具调用 ID，主幂等键的一半。
     *
     * <p>spike 结论：该 ID 在 {@code ChatResponse} 层可见且稳定，
     * 但<b>需自定义 {@code ToolCallingManager} 提取</b>——标准 {@code @Tool} 回调拿不到。
     */
    private String providerToolCallId;

    /**
     * 规范化参数指纹，兜底幂等键的一部分。
     *
     * <p>由应用对规范化后的参数计算（收件人排序、目标 ID 排序、正文归一化），
     * 保证"语义相同"的提案命中同一键。
     */
    private String canonicalPayloadHash;

    /**
     * createdAt + TTL（默认 24 小时）。
     *
     * <p><b>这是"派生值不落库"原则的唯一例外</b>：提案存续 24 小时，
     * 跨越用户修改设置的概率远高于只活 2 分钟的 Turn；且卡片上的过期倒计时
     * 是对用户的承诺，不该因为改了设置就跳变。改 TTL 只影响新建的提案。
     */
    private OffsetDateTime expiresAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    /** 批准/拒绝/过期/取消时刻。 */
    private OffsetDateTime decidedAt;

    /**
     * CANCELLED 时必填（check 约束保证）。
     *
     * <p>CANCELLED 与 REJECTED 的区别：REJECTED 是用户明确说不要；
     * CANCELLED 是系统发现提案已无法执行（目标邮件已被手动删除、发信账号已被移除、
     * 内容快照缺失、所属对话已被删除）。卡片因此变灰并显示本字段。
     */
    private String cancelReason;
}
