package com.xyzensun.emailcopilot.interfaces.mail.dto;

import com.xyzensun.emailcopilot.application.mail.model.MessagePageView;

import java.util.List;

public record MessagePageResponse(
        List<MessageSummaryResponse> items,
        int page,
        int size,
        long total) {

    public static MessagePageResponse from(MessagePageView view) {
        return new MessagePageResponse(
                view.items().stream().map(MessageSummaryResponse::from).toList(),
                view.page(), view.size(), view.total());
    }
}
