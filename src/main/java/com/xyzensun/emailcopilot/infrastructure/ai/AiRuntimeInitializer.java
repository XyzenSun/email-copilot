package com.xyzensun.emailcopilot.infrastructure.ai;

import com.xyzensun.emailcopilot.application.settings.AiSettingsService;
import com.xyzensun.emailcopilot.infrastructure.security.MasterKeyStatusSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * 在数据库初始化与主密钥自检完成后，从事实源恢复运行时 ChatModel。
 *
 * <p>{@link SmartInitializingSingleton} 在所有普通 singleton 完成初始化后执行，因此不会抢在 Flyway
 * 或 {@code MasterKeySelfCheck} 前读取表/解密。非法数据库配置或解密失败不捕获，直接让容器 refresh
 * 失败；AI 型号或 key 未配置则由 holder 正常保持空引用。
 */
@Component
public class AiRuntimeInitializer implements SmartInitializingSingleton {

    private final AiSettingsService aiSettingsService;
    private final MasterKeyStatusSource masterKeyStatusSource;

    public AiRuntimeInitializer(
            AiSettingsService aiSettingsService,
            MasterKeyStatusSource masterKeyStatusSource) {
        this.aiSettingsService = aiSettingsService;
        this.masterKeyStatusSource = masterKeyStatusSource;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // 读取已发布状态同时把顺序写成可执行依赖；状态源缺失时容器应直接装配失败，不能静默跳过恢复。
        masterKeyStatusSource.getMasterKeyStatus();
        aiSettingsService.initializeRuntime();
    }
}
