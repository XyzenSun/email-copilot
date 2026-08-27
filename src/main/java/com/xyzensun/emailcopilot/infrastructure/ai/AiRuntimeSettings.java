package com.xyzensun.emailcopilot.infrastructure.ai;

import com.xyzensun.emailcopilot.domain.enums.AiProvider;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;

import static java.util.Objects.requireNonNull;

/**
 * 已提交到数据库的 AI 连接配置快照。
 *
 * <p>API key 刻意不属于这个类型：它来自 {@code ExternalAccountSecretStore}，只在
 * {@link ChatModelHolder#reload(AiRuntimeSettings, String)} 的边界短暂传入。这样配置快照可以
 * 安全地出现在应用层日志或调试器里，也不会因为 record 的默认 {@code toString()} 展开凭据。
 *
 * <p>{@code baseUrl == null} 表示 provider 官方端点；自定义 URL 原样传递，不自动追加或删除
 * {@code /v1}，因为 OpenAI 兼容服务与 Anthropic 端点的路径约定不同。
 */
public record AiRuntimeSettings(
        AiProvider provider,
        String baseUrl,
        String model,
        int contextWindowK,
        int timeoutSeconds) {

    public static final int MIN_CONTEXT_WINDOW_K = 4;
    public static final int MAX_CONTEXT_WINDOW_K = 2_000;
    public static final int MIN_TIMEOUT_SECONDS = 5;
    public static final int MAX_TIMEOUT_SECONDS = 600;

    public static final String OPENAI_DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final String ANTHROPIC_DEFAULT_BASE_URL = "https://api.anthropic.com";

    public AiRuntimeSettings {
        requireNonNull(provider, "AI provider 不能为空");
        if (baseUrl != null) {
            validateBaseUrl(baseUrl);
        }
        if (model != null && model.isBlank()) {
            throw new IllegalArgumentException("AI model 不能为空白；未配置请使用 null");
        }
        if (contextWindowK < MIN_CONTEXT_WINDOW_K || contextWindowK > MAX_CONTEXT_WINDOW_K) {
            throw new IllegalArgumentException("AI contextWindowK 超出允许范围");
        }
        if (timeoutSeconds < MIN_TIMEOUT_SECONDS || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("AI timeoutSeconds 超出允许范围");
        }
    }

    /** 型号为 null 是首次部署的正常状态；空白型号由 API 和启动校验拒绝。 */
    public boolean modelConfigured() {
        return model != null && !model.isBlank();
    }

    /** API key 只检查是否有值，不复制、不规范化，也不进入本快照。 */
    public boolean readyWith(String apiKey) {
        return modelConfigured() && apiKey != null && !apiKey.isBlank();
    }

    public Duration timeout() {
        return Duration.ofSeconds(timeoutSeconds);
    }

    public String baseUrlOrDefault() {
        if (baseUrl != null) {
            return baseUrl;
        }
        return provider == AiProvider.OPENAI
                ? OPENAI_DEFAULT_BASE_URL
                : ANTHROPIC_DEFAULT_BASE_URL;
    }

    /**
     * 只接受无 user-info/query/fragment 的 HTTP(S) 基地址。
     *
     * <p>把校验放在快照构造时，未配置 key/model 的 PATCH 也不能把危险 URL 写进数据库后潜伏到
     * 下一次 reload；异常故意不回显 URL，避免其中夹带的 token 进入响应或日志。
     */
    private static void validateBaseUrl(String baseUrl) {
        if (baseUrl.isBlank()) {
            throw new IllegalArgumentException("AI baseUrl 不能为空；未配置请使用 null");
        }
        try {
            URI uri = new URI(baseUrl);
            String scheme = uri.getScheme();
            boolean httpScheme = scheme != null
                    && (scheme.toLowerCase(Locale.ROOT).equals("http")
                    || scheme.toLowerCase(Locale.ROOT).equals("https"));
            if (!uri.isAbsolute()
                    || !httpScheme
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException("AI baseUrl 必须是安全的 HTTP(S) 基地址");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("AI baseUrl 不是合法 URL");
        }
    }
}
