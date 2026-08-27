package com.xyzensun.emailcopilot.interfaces.mail.dto;

import com.xyzensun.emailcopilot.application.mail.model.ThreadDetailView;

import java.util.List;

public record ThreadDetailResponse(
        long threadId,
        int messageCount,
        List<MessageSummaryResponse> items) {

    public static ThreadDetailResponse from(ThreadDetailView view) {
        return new ThreadDetailResponse(
                view.threadId(),
                view.messageCount(),
                view.items().stream().map(MessageSummaryResponse::from).toList());
    }
}
