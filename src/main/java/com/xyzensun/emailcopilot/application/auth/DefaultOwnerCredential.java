package com.xyzensun.emailcopilot.application.auth;

/**
 * 首次启动创建的默认凭据（{@code API.md} §7.3、{@code DECISIONS.md} 211 行）。
 *
 * <p><b>不强制修改</b>，也不做初始化页面或「翻日志取随机口令」的流程——
 * 部署即可用是已确认的取舍。
 *
 * <p><b>已知风险（用户已确认接受）</b>：默认凭据未改且服务暴露公网时，
 * 任何扫描器都可登录并取得全部邮箱凭据与发信能力。这是自部署应用最常见的入侵入口。
 * 缓解手段仅为前端常驻横幅 + 启动日志警告。
 *
 * <p>{@link #PASSWORD} 除了首次创建，还用于登录时判定 {@code usingDefaultPassword}
 * ——那个布尔驱动前端的常驻横幅。
 */
final class DefaultOwnerCredential {

    static final String USERNAME = "admin";
    static final String PASSWORD = "admin123456";

    private DefaultOwnerCredential() {
    }
}
