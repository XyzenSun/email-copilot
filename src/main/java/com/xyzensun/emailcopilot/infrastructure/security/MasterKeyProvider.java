package com.xyzensun.emailcopilot.infrastructure.security;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * 持有外部凭据加密用的主密钥（{@code ARCHITECTURE.md} §7.2）。
 *
 * <p>密钥由环境变量注入，<b>既不入代码仓库也不入数据库</b>——因此仅取得数据库副本
 * 无法解出邮箱口令与 AI key，需要服务器环境与数据库同时失守。
 *
 * <p><b>未配置不等于错误</b>：全新部署还没有任何外部凭据时，没有密钥也能启动，
 * 只是加密能力不可用。「有密文却没有密钥」以及「当前密钥无法认证任一已有密文」
 * 都是致命状态，由 {@link MasterKeySelfCheck} 在启动时拦住。
 *
 * <p>格式非法（不是 Base64、或解出来不是 32 字节）则<b>直接让这个 bean 构造失败</b>，
 * 也就是启动失败。理由同上：带着一把用不了的密钥启动，等于把「凭据全部解不开」
 * 推迟到用户下次收信时才暴露。
 */
@Component
public class MasterKeyProvider {

    /** AES-256。32 字节以外的长度一律拒绝，不做「短了补零、长了截断」这类补救。 */
    private static final int REQUIRED_KEY_BYTES = 32;

    private static final String PROPERTY_NAME = "email-copilot.security.master-key";
    private static final String ENV_NAME = "EMAIL_COPILOT_MASTER_KEY";

    /** null 表示未配置。 */
    private final SecretKey key;

    MasterKeyProvider(SecurityProperties properties) {
        this.key = parse(properties.masterKey());
    }

    private static SecretKey parse(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException ex) {
            // 消息里不带原值：它就是密钥本身
            throw new MasterKeyException(
                    "%s（环境变量 %s）不是合法的标准 Base64".formatted(PROPERTY_NAME, ENV_NAME), ex);
        }
        if (decoded.length != REQUIRED_KEY_BYTES) {
            // 只说明配置格式，不回显用户实际给了多长；凭据的长度同样属于不得回显的信息
            throw new MasterKeyException(
                    "%s（环境变量 %s）格式不合法：应为 Base64 编码的 %d 字节 AES-256 密钥"
                            .formatted(PROPERTY_NAME, ENV_NAME, REQUIRED_KEY_BYTES));
        }
        return new SecretKeySpec(decoded, "AES");
    }

    public boolean isConfigured() {
        return key != null;
    }

    /**
     * @throws MasterKeyException 未配置主密钥。<b>不静默降级为明文存储</b>——
     *                            那会让「凭据已加密」这个前提在没有任何提示的情况下失效。
     */
    public SecretKey key() {
        if (key == null) {
            throw new MasterKeyException(
                    "未配置 %s（环境变量 %s），无法加解密外部凭据".formatted(PROPERTY_NAME, ENV_NAME));
        }
        return key;
    }

    /** 覆盖掉默认实现，避免密钥出现在任何日志或异常里。 */
    @Override
    public String toString() {
        return "MasterKeyProvider[configured=" + isConfigured() + "]";
    }
}
