package com.xyzensun.emailcopilot.interfaces.conversation.dto;

import java.util.List;

public record ConversationListResponse(List<ConversationSummaryResponse> items) {
}
