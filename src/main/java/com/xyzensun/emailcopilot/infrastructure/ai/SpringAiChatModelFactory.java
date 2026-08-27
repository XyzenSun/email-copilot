package com.xyzensun.emailcopilot.infrastructure.ai;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

import static java.util.Objects.requireNonNull;

/**
 * Spring AI 2.0.0 的 OpenAI / Anthropic 模型构造器。
 *
 * <p>2.0.0 GA 的公开 API 已把 {@code apiKey/baseUrl/model/timeout} 收到 provider options 中，
 * 再由 {@code OpenAiChatModel.builder()} / {@code AnthropicChatModel.builder()} 创建同步与异步
 * SDK client。这里不依赖 starter 自动装配 bean，因此五项连接配置都可以运行期重建。
 *
 * <p>两个 provider 都显式设 {@code maxRetries=0}。SDK 的隐式重试会让一次连接测试或模型调用
 * 实际等待时间超过用户保存的 {@code aiTimeoutSeconds}，并使上层整轮超时变得不可预测；重试策略
 * 必须由本项目的阶段/Turn 护栏决定，而不是藏在 provider client 里。
 */
@Component
public final class SpringAiChatModelFactory implements ChatModelFactory {

    private static final int SDK_MAX_RETRIES = 0;

    @Override
    public ChatModel create(AiRuntimeSettings settings, String apiKey) {
        requireNonNull(settings, "AI runtime settings 不能为空");
        if (!settings.readyWith(apiKey)) {
            throw new AiModelConstructionException("AI 型号或 API key 尚未配置");
        }

        String safeBaseUrl = validateBaseUrl(settings.baseUrlOrDefault());
        try {
            return switch (settings.provider()) {
                case OPENAI -> buildOpenAiModel(settings, safeBaseUrl, apiKey);
                case ANTHROPIC -> buildAnthropicModel(settings, safeBaseUrl, apiKey);
            };
        } catch (AiModelConstructionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // 不把 SDK 异常作为 cause 带出：其 message 可能包含 Authorization 或 provider 响应正文。
            throw new AiModelConstructionException("无法构造 AI 模型，请检查连接配置");
        }
    }

    private static OpenAiChatModel buildOpenAiModel(
            AiRuntimeSettings settings, String baseUrl, String apiKey) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(settings.model())
                .timeout(settings.timeout())
                .maxRetries(SDK_MAX_RETRIES)
                .build();
        return OpenAiChatModel.builder()
                .options(options)
                .build();
    }

    private static AnthropicChatModel buildAnthropicModel(
            AiRuntimeSettings settings, String baseUrl, String apiKey) {
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(settings.model())
                .timeout(settings.timeout())
                .maxRetries(SDK_MAX_RETRIES)
                .build();
        return AnthropicChatModel.builder()
                .options(options)
                .build();
    }

    /**
     * 只接受无 user-info/query/fragment 的 HTTP(S) 基地址。
     *
     * <p>除了防止 SDK 在很晚的网络调用阶段才报错，这也避免把 token 偷塞进 URL；否则 URL 会被
     * 正常的连接配置日志记录，等价于凭据泄漏。异常文案故意不回显原 URL。
     */
    private static String validateBaseUrl(String baseUrl) {
        try {
            URI uri = new URI(baseUrl);
            String scheme = uri.getScheme();
            boolean httpScheme = scheme != null
                    && (scheme.toLowerCase(Locale.ROOT).equals("http")
                    || scheme.toLowerCase(Locale.ROOT).equals("https"));
            if (!httpScheme
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new AiModelConstructionException("AI baseUrl 必须是安全的 HTTP(S) 基地址");
            }
            return uri.toASCIIString();
        } catch (URISyntaxException exception) {
            throw new AiModelConstructionException("AI baseUrl 不是合法 URL");
        }
    }
}
