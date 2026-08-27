package com.xyzensun.emailcopilot.application.settings.model;

/**
 * PATCH 字段的三态值：未出现、出现且有值、出现且显式为 {@code null}。
 *
 * <p>普通 nullable 字段无法区分前两种语义，若直接用它合并请求，省略字段会被误清空，
 * 或者显式 {@code null} 永远无法清掉旧配置。
 */
public record MailAccountPatchValue<T>(boolean present, T value) {

    public static <T> MailAccountPatchValue<T> absent() {
        return new MailAccountPatchValue<>(false, null);
    }

    public static <T> MailAccountPatchValue<T> present(T value) {
        return new MailAccountPatchValue<>(true, value);
    }
}
