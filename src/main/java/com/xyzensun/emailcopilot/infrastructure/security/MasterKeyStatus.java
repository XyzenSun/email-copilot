package com.xyzensun.emailcopilot.infrastructure.security;

/**
 * 主密钥的只读运行状态。
 *
 * <p>这是设置应用层读取主密钥状态的安全边界：只暴露两个布尔值，
 * 不暴露主密钥、密文、解密出的凭据、凭据长度或任何异常原文。
 * {@code masterKeyMatchesCiphertext} 表示启动自检已经用当前密钥验证了数据库中的
 * <b>每一行</b>外部凭据密文；没有密文时按“没有不匹配项”处理。
 *
 * @param masterKeyPresent            是否由环境属性注入合法的 AES-256 主密钥
 * @param masterKeyMatchesCiphertext  当前主密钥是否通过全部已有密文的认证校验
 */
public record MasterKeyStatus(
        boolean masterKeyPresent,
        boolean masterKeyMatchesCiphertext) {
}
