package com.xyzensun.emailcopilot.infrastructure.security;

/**
 * AES-GCM 加解密失败。
 *
 * <p>解密失败的可能原因有三个，而 GCM 有意<b>不区分</b>它们（区分会泄露信息给攻击者）：
 * 主密钥不对、AAD 不对（密文被搬到了别的账号/凭据类型下）、密文或 tag 被篡改。
 *
 * <p>因此排查时要按顺序确认：主密钥是不是换过、
 * {@code (secret_type, mail_account_id)} 是不是和写入时一致。
 */
public class SecretCipherException extends IllegalStateException {

    public SecretCipherException(String message, Throwable cause) {
        super(message, cause);
    }
}
