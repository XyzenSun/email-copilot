package com.xyzensun.emailcopilot.infrastructure.security;

/**
 * 主密钥缺失、格式非法、无法认证已有密文，或在没有主密钥时被要求加解密。
 *
 * <p>启动自检（{@link MasterKeySelfCheck}）抛出它即让应用<b>启动失败</b>，这是刻意的：
 * 静默生成新密钥，或放过一把格式合法但不属于既有密文的密钥，都会让邮箱口令与 AI key
 * 到真正使用时才失败；用户看到的只是「所有邮箱突然连不上」，没有线索指向主密钥。
 * AES-GCM 认证失败也可能表示密文或 AAD 被篡改，同样不能带病启动。
 *
 * <p>异常消息只给可行动的安全摘要：可以写密文条数与配置项名，
 * <b>绝不写密钥、凭据明文、密文、nonce 或它们的实际长度</b>。
 */
public class MasterKeyException extends IllegalStateException {

    public MasterKeyException(String message) {
        super(message);
    }

    public MasterKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}
