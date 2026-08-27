package com.xyzensun.emailcopilot.interfaces.settings.dto;

import com.xyzensun.emailcopilot.application.settings.SenderRuleApplicationService.SenderRuleView;

import java.time.OffsetDateTime;

/** 发件人规则响应，对应 {@code openapi.yaml SenderRule}。 */
public record SenderRuleResponse(
        Long id,
        String ruleType,
        String domainPattern,
        Boolean enabled,
        OffsetDateTime updatedAt) {

    public static SenderRuleResponse from(SenderRuleView view) {
        return new SenderRuleResponse(
                view.id(), view.ruleType(), view.domainPattern(), view.enabled(), view.updatedAt());
    }
}
