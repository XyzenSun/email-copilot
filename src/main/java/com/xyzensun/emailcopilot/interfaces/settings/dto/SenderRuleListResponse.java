package com.xyzensun.emailcopilot.interfaces.settings.dto;

import java.util.List;

/** 不分页的发件人规则列表。 */
public record SenderRuleListResponse(List<SenderRuleResponse> items) {

    public SenderRuleListResponse {
        items = List.copyOf(items);
    }
}
