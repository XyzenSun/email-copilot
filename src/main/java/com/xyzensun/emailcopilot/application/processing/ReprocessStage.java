package com.xyzensun.emailcopilot.application.processing;

/**
 * 手动重新处理暴露给用户的四个流水线步骤（{@code ProcessingStage} 的子集）。
 *
 * <p>不直接复用 {@link com.xyzensun.emailcopilot.domain.enums.ProcessingStage}：
 * 后者还含 {@code sender_rule}/{@code language_detection}/{@code done}，而手动入口必须拒绝
 * 这三个（{@code design.md} §2.2）。独立枚举让非法值在契约解析处就落 400，不进 service。
 *
 * <p>{@code language_detection} 不单独暴露：它与翻译绑定同一开关，是确定性子步骤、无独立产物；
 * 手动「翻译」内部先跑语言检测（{@code design.md} §6.3）。
 */
public enum ReprocessStage {
    SPAM_JUDGMENT("spam_judgment"),
    CLASSIFICATION("classification"),
    TRANSLATION("translation"),
    SUMMARY("summary");

    private final String value;

    ReprocessStage(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static ReprocessStage fromValue(String value) {
        for (ReprocessStage stage : values()) {
            if (stage.value.equals(value)) {
                return stage;
            }
        }
        return null;
    }
}
