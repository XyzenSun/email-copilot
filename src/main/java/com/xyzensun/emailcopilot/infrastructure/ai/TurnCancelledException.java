package com.xyzensun.emailcopilot.infrastructure.ai;

/**
 * 工具调用循环被用户取消（非流式下）。
 *
 * <p>非流式 {@code ChatClient.call()} 期间，单次 LLM 生成无法中断；但工具循环间——
 * 模型回灌工具结果、想调下一个工具前——{@link ConversationToolCallingManager#executeToolCalls}
 * 入口会检查取消标志，命中即抛本异常，中断后续工具执行。
 * 由 {@code TurnApplicationService.driveStream} 捕获后转 {@code handleCancelled}。
 */
public class TurnCancelledException extends RuntimeException {
    private final long turnId;

    public TurnCancelledException(long turnId) {
        super("Turn " + turnId + " 已取消");
        this.turnId = turnId;
    }

    public long getTurnId() {
        return turnId;
    }
}
