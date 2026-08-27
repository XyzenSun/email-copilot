package com.xyzensun.emailcopilot.domain.pipeline;

import com.xyzensun.emailcopilot.domain.enums.SenderRuleType;
import com.xyzensun.emailcopilot.domain.sender.DomainPattern;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.SenderRule;

import java.util.List;

import static java.util.Objects.requireNonNull;

/** 按已认证域名执行启用的发件人规则，并保证 block 高于 trust。 */
public final class SenderRuleEvaluator {

    public SenderRuleOutcome evaluate(String authenticatedDomain, List<SenderRule> rules) {
        requireNonNull(rules, "发件人规则集合不能为空");
        if (authenticatedDomain == null || authenticatedDomain.isBlank()) {
            return SenderRuleOutcome.MISS;
        }

        boolean trustMatched = false;
        for (SenderRule rule : rules) {
            if (rule == null || !Boolean.TRUE.equals(rule.getEnabled())) {
                continue;
            }
            if (!DomainPattern.parse(rule.getDomainPattern()).matches(authenticatedDomain)) {
                continue;
            }
            if (rule.getRuleType() == SenderRuleType.BLOCK) {
                return SenderRuleOutcome.BLOCK;
            }
            if (rule.getRuleType() == SenderRuleType.TRUST) {
                trustMatched = true;
            }
        }
        return trustMatched ? SenderRuleOutcome.TRUST : SenderRuleOutcome.MISS;
    }
}
