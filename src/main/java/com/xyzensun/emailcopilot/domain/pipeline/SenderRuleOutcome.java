package com.xyzensun.emailcopilot.domain.pipeline;

/** 已认证发件人域名的确定性规则结果。 */
public enum SenderRuleOutcome {
    BLOCK,
    TRUST,
    MISS
}
