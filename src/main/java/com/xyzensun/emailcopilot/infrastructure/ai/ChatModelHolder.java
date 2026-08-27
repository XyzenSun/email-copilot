package com.xyzensun.emailcopilot.infrastructure.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

/**
 * 运行期可热替换的 {@link ChatModel} 引用。
 *
 * <p>模型构造在 volatile 引用之外完成；只有构造成功后才做一次引用赋值。因此正在执行的
 * 应用服务只要先把 {@link #current()} 取到局部变量，就会继续使用旧模型完成本次调用，配置
 * 更新不会中断它。读路径不加锁，重建路径用短的 synchronized 保护更新顺序。
 *
 * <p>未配置型号或 key 时引用设为 {@code null}。这是刻意的三态边界：旧模型不能在用户清空
 * 配置后继续被新请求误用；构造新模型失败时则保持旧引用，避免一次非法 patch 破坏当前可用连接。
 */
@Component
public class ChatModelHolder {

    private static final Logger log = LoggerFactory.getLogger(ChatModelHolder.class);

    private final ChatModelFactory modelFactory;
    private volatile ChatModel current;

    /** Spring 容器只注入构造器；模型本身不会在启动时创建。 */
    public ChatModelHolder(ChatModelFactory modelFactory) {
        this.modelFactory = requireNonNull(modelFactory, "ChatModelFactory 不能为空");
    }

    /**
     * 取得当前模型的一次性快照。
     *
     * <p>调用方必须把返回值保存到局部变量，再在整个远程调用期间复用它；不要在一次调用中
     * 多次读取 holder，否则热替换会让同一轮请求混用两套配置。
     */
    public ChatModel current() {
        return current;
    }

    public boolean isReady() {
        return current != null;
    }

    /**
     * 要么原子切换到新模型，要么保留旧模型；不在构造期间短暂写入半成品。
     *
     * @param settings 已从 {@code app_setting} 读出的不可变快照
     * @param apiKey   仅在构造边界使用的明文 key，不会由 holder 保存
     */
    public synchronized void reload(AiRuntimeSettings settings, String apiKey) {
        requireNonNull(settings, "AI runtime settings 不能为空");

        if (!settings.readyWith(apiKey)) {
            // 清空配置必须立即阻止后续新调用继续使用旧凭据；旧调用持有自己的局部引用，
            // 不受这次清空影响并可自然完成。
            current = null;
            // baseUrl/model 都是用户输入，不写日志：自定义端点路径可能夹带 token，且真实验收配置
            // 来自不允许打印内容的 env 文件。这里只记录不含值的状态，足以判断为何 holder 被清空。
            log.info("AI 运行时模型已清空: provider={} modelConfigured={} apiKeyConfigured={}",
                    settings.provider().getValue(), settings.modelConfigured(),
                    apiKey != null && !apiKey.isBlank());
            return;
        }

        ChatModel replacement = modelFactory.create(settings, apiKey);
        if (replacement == null) {
            throw new AiModelConstructionException("AI 模型构造器返回空引用");
        }
        // 赋值是 volatile 写：读线程只会看到旧对象或完整的新对象，不会看到构造中间态。
        current = replacement;
        // 不记录 baseUrl/model 的实际值，避免自定义路径中的 token 或测试 env 内容进入日志。
        log.info("AI 运行时模型已重载: provider={}", settings.provider().getValue());
    }

    /**
     * 为必须执行 AI 的用例提供显式的未配置出口；流水线等可选路径仍应使用 {@link #current()}
     * 并在 null 时跳过，而不是调用此方法。
     */
    public ChatModel requireCurrent() {
        ChatModel model = current;
        if (model == null) {
            throw new AiNotConfiguredException();
        }
        return model;
    }
}
