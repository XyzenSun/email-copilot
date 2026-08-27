package com.xyzensun.emailcopilot.interfaces.auth.dto;

/**
 * 当前登录状态（{@code openapi.yaml} 的 {@code SessionInfo}）。
 *
 * <p>三个字段全部 required——未登录时也要有 {@code username: null} 与
 * {@code usingDefaultPassword: false}，让前端的类型是一个固定形状而不用做可选判空。
 *
 * @param username             未登录时为 null
 * @param usingDefaultPassword 为 true 时前端显示<b>常驻横幅</b>而非一次性弹窗
 *                             （{@code API.md} §7.3）：一次性提示点掉后再也不出现，
 *                             而这条提醒需要一直在
 */
public record SessionInfoResponse(boolean authenticated, String username, boolean usingDefaultPassword) {

    /** 未登录：注意这仍是 200 响应，未登录是一个合法答案而非错误（{@code API.md} §7.1）。 */
    public static SessionInfoResponse anonymous() {
        return new SessionInfoResponse(false, null, false);
    }

    public static SessionInfoResponse authenticated(String username, boolean usingDefaultPassword) {
        return new SessionInfoResponse(true, username, usingDefaultPassword);
    }
}
