package com.xyzensun.emailcopilot.application.mail.model;

import java.util.List;

public record ThreadDetailView(
        long threadId,
        int messageCount,
        List<MessageSummaryView> items) {
}
