package com.xyzensun.emailcopilot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 全局时钟。
 *
 * <p><b>约定：需要当前时间的代码从注入的 {@link Clock} 取，不直接调
 * {@code Instant.now()} / {@code OffsetDateTime.now()}。</b>
 *
 * <p>理由是这些时间参与的都是「到点才生效」的判定，而它们没法用真实时间测：
 * <ul>
 *   <li>登录失败锁定 15 分钟（{@code API.md} §7.4）——测「15 分钟后能重新登录」
 *       不可能真等 15 分钟；</li>
 *   <li>{@code pending_action.expires_at} 的 24 小时有效期（阶段 8）；</li>
 *   <li>处理租约 {@code claim_until} 的到期重领（阶段 4）。</li>
 * </ul>
 * 直接调 {@code now()} 的代码只能靠 {@code Thread.sleep} 或改系统时间来测，
 * 于是实际上不会有人测——而这三处判定写错的后果都是静默的：
 * 锁定永不解除、提案永不过期、崩溃的任务永远没人接手。
 *
 * <p>数据库层的时间仍由 {@code TimestampAutoFillHandler} 与列默认值 {@code now()} 负责，
 * 那些是审计字段、不参与业务判定，不需要可控。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
