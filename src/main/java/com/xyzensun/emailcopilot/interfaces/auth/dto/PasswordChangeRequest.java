package com.xyzensun.emailcopilot.interfaces.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 改口令请求（{@code openapi.yaml} 的 {@code PasswordChangeRequest}）。
 *
 * <p>新口令最小长度 8，与 {@code openapi.yaml} 的 {@code minLength: 8} 对齐——
 * 两边不一致时前端会放过一个后端要拒的值，用户看到的是「填完才报错」。
 */
public record PasswordChangeRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 8, message = "新口令至少 8 个字符") String newPassword) {

    /** 两个字段都是口令，全部隐藏。理由同 {@link LoginRequest#toString()}。 */
    @Override
    public String toString() {
        return "PasswordChangeRequest[currentPassword=<已隐藏>, newPassword=<已隐藏>]";
    }
}
