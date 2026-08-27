package com.xyzensun.emailcopilot.application.mail.model;

import com.xyzensun.emailcopilot.domain.enums.MessageCategory;

import java.time.OffsetDateTime;

public record MessageListQuery(
        int page,
        int size,
        Long accountId,
        MessageCategory category,
        Long tagId,
        DirectionSelection direction,
        OffsetDateTime receivedAfter,
        OffsetDateTime receivedBefore,
        boolean includeSpam) {

    public enum DirectionSelection {
        INBOUND,
        OUTBOUND,
        ALL
    }
}
