package com.xyzensun.emailcopilot.application.auth;

import com.xyzensun.emailcopilot.interfaces.error.ApiError;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 登录失败限流：连续 **5 次锁定 15 分钟**（{@code API.md} §7.4）。
 *
 * <p><b>计数与锁定状态在内存中，重启清零</b>（{@code DATABASE.md} §8.1：不为限流建表）。
 * 单用户系统若暴露公网，防暴力破解是必需项，但重启清零可接受。
 *
 * <p><b>锁定是全局的</b>：系统只有一个账号，因此攻击者可以故意连续失败把所有者锁在门外，
 * 解锁手段是重启应用。此取舍已在 {@code API.md} §7.4 确认接受。
 * 也正因为只有一个账号，这里用单个计数器而不是按用户名或 IP 分桶——
 * 按 IP 分桶反而给了攻击者绕过手段（换 IP 即重新计数），却挡不住真正的暴力破解。
 *
 * <p><b>锁定期间即使口令正确也拒绝</b>，因此调用点必须在校验口令<b>之前</b>调
 * {@link #ensureNotLocked()}。放到校验之后就变成了「口令对就放行」，
 * 限流对拿到正确口令的攻击者形同虚设。
 */
@Component
class LoginAttemptGuard {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptGuard.class);

    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    private final Clock clock;

    private int consecutiveFailures;
    private Instant lockedUntil;

    LoginAttemptGuard(Clock clock) {
        this.clock = clock;
    }

    /**
     * @throws ApiException {@link ApiError#LOGIN_ATTEMPTS_EXCEEDED}，{@code detail} 给出剩余时间
     */
    synchronized void ensureNotLocked() {
        if (lockedUntil == null) {
            return;
        }
        Instant now = clock.instant();
        if (!now.isBefore(lockedUntil)) {
            // 锁定期已过，重新开始计数
            lockedUntil = null;
            consecutiveFailures = 0;
            return;
        }
        long remainingMinutes = Math.max(1, Duration.between(now, lockedUntil).toMinutes());
        throw new ApiException(ApiError.LOGIN_ATTEMPTS_EXCEEDED,
                "请在 %d 分钟后重试".formatted(remainingMinutes));
    }

    synchronized void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            lockedUntil = clock.instant().plus(LOCKOUT_DURATION);
            log.warn("连续 {} 次登录失败，锁定 {} 分钟", consecutiveFailures, LOCKOUT_DURATION.toMinutes());
        }
    }

    synchronized void recordSuccess() {
        consecutiveFailures = 0;
        lockedUntil = null;
    }
}
