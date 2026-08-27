package com.xyzensun.emailcopilot.application.mail.model;

import java.util.List;

public record MessagePageView(
        List<MessageSummaryView> items,
        int page,
        int size,
        long total) {
}
