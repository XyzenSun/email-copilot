package com.xyzensun.emailcopilot.infrastructure.ai;

import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * AI 连接测试的业务结果。
 *
 * <p>连接测试请求本身已被正确处理，因此 provider 失败和超时也由 HTTP 200 携带本类型返回；
 * 应用层只需把当前配置缺失的 {@link AiNotConfiguredException} 映射为 409。
 */
public record AiTestResult(String status, long latencyMs, String message) {

    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";
    public static final String TIMEOUT = "timeout";
    private static final Set<String> ALLOWED_STATUSES = Set.of(SUCCEEDED, FAILED, TIMEOUT);

    public AiTestResult {
        requireNonNull(status, "AI 测试状态不能为空");
        requireNonNull(message, "AI 测试消息不能为空");
        if (!ALLOWED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("AI 测试状态非法");
        }
        if (latencyMs < 0) {
            throw new IllegalArgumentException("AI 测试耗时不能为负数");
        }
    }

    public static AiTestResult succeeded(long latencyMs) {
        return new AiTestResult(SUCCEEDED, latencyMs, "AI 连接正常");
    }

    public static AiTestResult failed(long latencyMs) {
        return new AiTestResult(FAILED, latencyMs, "AI 连接失败");
    }

    public static AiTestResult failed(long latencyMs, int statusCode) {
        return new AiTestResult(FAILED, latencyMs, "AI 连接失败（HTTP %d）".formatted(statusCode));
    }

    public static AiTestResult timeout(long latencyMs) {
        return new AiTestResult(TIMEOUT, latencyMs, "AI 连接超时");
    }
}
