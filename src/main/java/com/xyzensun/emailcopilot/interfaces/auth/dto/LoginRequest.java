package com.xyzensun.emailcopilot.interfaces.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求（{@code openapi.yaml} 的 {@code LoginRequest}）。
 *
 * <p>只做「非空」这一层校验。口令的长度、复杂度规则<b>不在登录时校验</b>——
 * 那会把「口令太短」和「口令不对」区分开，等于告诉攻击者哪些猜测格式正确。
 * 长度要求只在改口令时生效（{@link PasswordChangeRequest}）。
 */
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password) {

    /**
     * <b>必须覆盖：record 的默认 {@code toString} 会把口令原文展开。</b>
     *
     * <p>Spring MVC 在 DEBUG 级别就会打印反序列化后的参数
     * （{@code Read "application/json" to [LoginRequest[...]]}），
     * 于是任何一次「把日志调成 DEBUG 排查问题」都会把明文口令写进日志文件——
     * 而日志会被收集、转发、长期留存，甚至贴进工单。
     *
     * <p>用户名不是凭据，保留它有排查价值。
     */
    @Override
    public String toString() {
        return "LoginRequest[username=" + username + ", password=<已隐藏>]";
    }
}
