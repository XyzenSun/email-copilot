package com.xyzensun.emailcopilot.interfaces.conversation.dto;

import java.util.List;

public record PendingActionListResponse(List<PendingActionSummaryResponse> items) {
}
