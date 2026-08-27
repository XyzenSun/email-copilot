package com.xyzensun.emailcopilot.domain.enums;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * 待审批动作的类型（{@code DATABASE.md} §5.4）。
 *
 * <p><b>只有这三种动作需要审批</b>：{@code SEND_EMAIL}（对外不可撤回）、
 * {@code SAVE_DRAFT}（本地副作用）、{@code LOCAL_DELETE}（不可逆）。
 *
 * <p><b>没有任何标签动作。</b>标签是可逆的、低风险的、随时能手动纠正的；为它引入一条审批链路，
 * 用户只会被迫逐条点击确认无关紧要的变更，反而稀释审批本身的严肃性。
 * 对话 AI 因此不持有任何标签工具，AI 打得不满意用户手动改即可（{@code DATABASE.md} §4.2）。
 *
 * <p><b>也没有 {@code archive_thread}</b>：邮件会话没有归档概念（{@code thread_node} 无
 * {@code archived} 列），要让会话消失用 {@code LOCAL_DELETE}。归档只存在于 conversation。
 *
 * <p>三种动作的执行代价不同（{@code DATABASE.md} §5.6）：{@code SAVE_DRAFT} 与
 * {@code LOCAL_DELETE} 都是本地数据库事务，可在批准的同一事务内直达 succeeded；
 * 只有 {@code SEND_EMAIL} 要连 SMTP，才真正经历 executing 中间态与"已提交但响应丢失"的风险。
 * <b>不要给三种动作套同一套异步流程。</b>
 */
public enum ActionType implements IEnum<String> {

    SEND_EMAIL("send_email"),
    SAVE_DRAFT("save_draft"),
    LOCAL_DELETE("local_delete");

    private final String value;

    ActionType(String value) {
        this.value = value;
    }

    @Override
    public String getValue() {
        return value;
    }
}
