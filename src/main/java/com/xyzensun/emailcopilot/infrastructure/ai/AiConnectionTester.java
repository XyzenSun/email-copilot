package com.xyzensun.emailcopilot.infrastructure.ai;

import com.anthropic.errors.AnthropicServiceException;
import com.openai.errors.OpenAIServiceException;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * 使用当前 holder 引用执行一次最小 AI 连接测试。
 *
 * <p>测试提示固定且不带工具，避免按钮本身触发任何副作用或让 provider 返回大段内容。
 * holder 在方法开始时只读取一次；如果配置随后热替换，本次请求仍使用已经取得的旧引用。
 * provider 原始异常只用于本地分类，不进入返回消息、日志或异常 cause。
 */
@Component
public class AiConnectionTester {

    /** 这段文字是协议的一部分，修改它会改变连接测试的请求内容。 */
    public static final String FIXED_TEST_PROMPT = "Reply with exactly: OK";

    private final ChatModelHolder modelHolder;
    private final LongSupplier nanoTimeSource;

    @Autowired
    public AiConnectionTester(ChatModelHolder modelHolder) {
        this(modelHolder, System::nanoTime);
    }

    AiConnectionTester(ChatModelHolder modelHolder, LongSupplier nanoTimeSource) {
        this.modelHolder = java.util.Objects.requireNonNull(modelHolder, "ChatModelHolder 不能为空");
        this.nanoTimeSource = java.util.Objects.requireNonNull(nanoTimeSource, "计时器不能为空");
    }

    /**
     * @return 成功、provider 失败或超时，三种结果都属于正常的 200 业务响应
     * @throws AiNotConfiguredException 当前没有模型可供测试，应用层映射为 409
     */
    public AiTestResult testConnection() {
        ChatModel model = modelHolder.requireCurrent();
        long startedAt = nanoTimeSource.getAsLong();
        try {
            model.call(new Prompt(FIXED_TEST_PROMPT));
            return AiTestResult.succeeded(elapsedMillis(startedAt));
        } catch (RuntimeException exception) {
            long latencyMs = elapsedMillis(startedAt);
            if (isTimeout(exception)) {
                return AiTestResult.timeout(latencyMs);
            }
            Integer statusCode = providerStatusCode(exception);
            return statusCode == null
                    ? AiTestResult.failed(latencyMs)
                    : AiTestResult.failed(latencyMs, statusCode);
        }
    }

    private long elapsedMillis(long startedAt) {
        long elapsedNanos = nanoTimeSource.getAsLong() - startedAt;
        // nanoTime 只应单调递增；时钟异常时仍返回合法、可序列化的非负值。
        return Math.max(0L, elapsedNanos / 1_000_000L);
    }

    private static boolean isTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof TimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof InterruptedIOException) {
                return true;
            }
            String className = current.getClass().getName();
            // 两个 SDK 在不同 HTTP backend/版本下可能包装自己的 timeout 类型；按类名识别
            // 比读取异常 message 安全，后者可能包含 URL、header 或 provider 原始正文。
            if (className.endsWith("TimeoutException")
                    || className.endsWith("ReadTimeoutException")
                    || className.endsWith("ConnectTimeoutException")) {
                return true;
            }
            current = unwrapAsyncFailure(current);
        }
        return false;
    }

    private static Integer providerStatusCode(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof OpenAIServiceException openAiFailure) {
                return openAiFailure.statusCode();
            }
            if (current instanceof AnthropicServiceException anthropicFailure) {
                return anthropicFailure.statusCode();
            }
            current = unwrapAsyncFailure(current);
        }
        return null;
    }

    private static Throwable unwrapAsyncFailure(Throwable failure) {
        if ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure.getCause();
    }
}
