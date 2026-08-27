package com.xyzensun.emailcopilot.infrastructure.ai;

import java.math.BigDecimal;

/** 已通过固定 schema 校验的垃圾倾向评分。 */
public record SpamJudgmentResult(BigDecimal spamScore) {
}
