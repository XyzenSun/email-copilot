package com.xyzensun.emailcopilot.application.conversation.model;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 对话详情：所有轮次一次给全（对话是轻量级多轮容器，不预期有几千轮）。
 */
public record ConversationDetailView(
        long id,
        String title,
        boolean archived,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<TurnView> turns) {
}
