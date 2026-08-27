package com.xyzensun.emailcopilot.infrastructure.ai;

/**
 * 当前没有可执行的 AI 模型。
 *
 * <p>这是首次部署或用户主动清空型号/key 时的正常状态，不是 provider 故障。应用层应将它
 * 映射为已登记的 {@code AI_NOT_CONFIGURED}（409），而不是当作 5xx；收信入库路径也不应
 * 因此被阻塞。
 */
public class AiNotConfiguredException extends IllegalStateException {

    public AiNotConfiguredException() {
        super("AI 尚未配置");
    }
}
