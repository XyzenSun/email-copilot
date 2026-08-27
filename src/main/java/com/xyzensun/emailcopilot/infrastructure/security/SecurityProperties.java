package com.xyzensun.emailcopilot.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 安全相关的外部配置。
 *
 * @param masterKey 外部凭据加密用的主密钥，<b>Base64 编码的 32 字节</b>（AES-256）。
 *                  由环境变量 {@code EMAIL_COPILOT_MASTER_KEY} 注入，
 *                  {@code application.yml} 里只写占位符、<b>不写值</b>。
 *                  未配置时为空串。
 *
 *                  <p>为什么经 Spring 属性中转而不直接 {@code System.getenv()}：
 *                  测试改不了环境变量，于是「缺密钥 + 库里已有密文 → 启动必须失败」
 *                  这条就只能手工验一次。手工验的东西会在后面某次重构里悄悄失效，
 *                  而失效方式恰好是最坏的那种——静默生成新密钥，既有邮箱口令与 AI key
 *                  全部永久解不开，而系统表面正常启动，用户只看到「所有邮箱突然连不上」。
 *                  走属性后 {@link MasterKeySelfCheck} 的四种组合都能进自动化测试。
 */
@ConfigurationProperties("email-copilot.security")
public record SecurityProperties(String masterKey) {

    /**
     * <b>必须覆盖：record 默认 {@code toString()} 会把主密钥原文展开。</b>
     *
     * <p>配置对象很容易在“打印当前配置排查问题”时整体进日志；如果沿用默认实现，
     * 一行 {@code log.debug("security={}", properties)} 就会把主密钥永久写进日志文件。
     * 对外只保留“是否已配置”这个布尔，不给明文、掩码或长度。
     */
    @Override
    public String toString() {
        return "SecurityProperties[masterKeyConfigured="
               + (masterKey != null && !masterKey.isBlank()) + "]";
    }
}
