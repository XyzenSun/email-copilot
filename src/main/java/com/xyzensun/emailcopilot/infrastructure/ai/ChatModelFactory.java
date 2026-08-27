package com.xyzensun.emailcopilot.infrastructure.ai;

import org.springframework.ai.chat.model.ChatModel;

/**
 * 根据不可变连接快照构造一个独立的 {@link ChatModel}。
 *
 * <p>把构造职责抽成这个小接口，是为了让 holder 在模型完全构造成功后才替换引用；
 * 生产实现始终构造真实 provider client，真实调用由外部 API 验收覆盖。
 */
@FunctionalInterface
public interface ChatModelFactory {

    /**
     * @param settings 不含 API key 的连接配置快照
     * @param apiKey   仅在本次构造边界使用的明文 key，不得保存或记录
     * @return 已完全构造、但尚未发出网络请求的模型
     * @throws AiModelConstructionException 配置非法或模型无法构造
     */
    ChatModel create(AiRuntimeSettings settings, String apiKey);
}
