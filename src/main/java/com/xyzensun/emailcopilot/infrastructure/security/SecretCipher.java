package com.xyzensun.emailcopilot.infrastructure.security;

import com.xyzensun.emailcopilot.domain.enums.SecretType;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 外部凭据的 AES-GCM 加解密（{@code DATABASE.md} §8.2、{@code ARCHITECTURE.md} §7.2）。
 *
 * <p>与登录口令的处理方式<b>不可互换</b>：登录口令只需验证，用不可逆的加盐哈希；
 * 这里的四种凭据必须在运行时还原原文去连接外部服务，因此只能可逆加密。
 *
 * <table border="1">
 *   <caption>参数选择</caption>
 *   <tr><th>项</th><th>取值</th><th>为什么</th></tr>
 *   <tr><td>算法</td><td>{@code AES/GCM/NoPadding}，256 位</td>
 *       <td>带认证。密文被篡改时解密直接失败，而不是给出一段垃圾明文——
 *           后者会被当成口令拿去登录邮箱，触发对方的失败锁定</td></tr>
 *   <tr><td>nonce</td><td>12 字节，<b>每次新生成</b></td>
 *       <td>GCM 的 nonce 复用是灾难性的：同一密钥下两条消息用同一 nonce，
 *           攻击者可以直接推出这两条明文的异或</td></tr>
 *   <tr><td>tag</td><td>128 位</td><td>GCM 默认强度</td></tr>
 *   <tr><td>AAD</td><td>{@code secretType + ':' + mailAccountId}</td>
 *       <td>把密文绑定到「哪个账号的哪类凭据」。密文被搬到另一行时解密失败，
 *           而不是让 A 账号的口令被当作 B 账号的口令去登录</td></tr>
 * </table>
 */
@Component
public class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    /** AI API key 不绑定邮箱账号时，AAD 里 id 的位置写这个字面值。 */
    private static final String NO_MAIL_ACCOUNT = "null";

    private final MasterKeyProvider masterKeyProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    public SecretCipher(MasterKeyProvider masterKeyProvider) {
        this.masterKeyProvider = masterKeyProvider;
    }

    /**
     * @param mailAccountId AI API key 时为 null
     * @throws MasterKeyException 未配置主密钥。<b>不静默降级为明文</b>
     */
    public EncryptedSecret encrypt(SecretType secretType, Long mailAccountId, String plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = newCipher(Cipher.ENCRYPT_MODE, nonce, secretType, mailAccountId);
            return new EncryptedSecret(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)), nonce);
        } catch (GeneralSecurityException ex) {
            // 消息里不带明文，也不带它的长度
            throw new SecretCipherException("加密 %s 凭据失败".formatted(secretType.getValue()), ex);
        }
    }

    /**
     * @throws SecretCipherException 主密钥不对、AAD 不对或密文被篡改（GCM 有意不区分三者）
     */
    public String decrypt(SecretType secretType, Long mailAccountId, byte[] ciphertext, byte[] nonce) {
        byte[] plaintext = decryptBytes(secretType, mailAccountId, ciphertext, nonce);
        try {
            return new String(plaintext, StandardCharsets.UTF_8);
        } finally {
            // String 无法主动清零，但中间 byte[] 没有继续留在堆中的必要，使用完立即覆盖。
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /**
     * 只执行 AES-GCM 认证，不把凭据构造成不可清零的 {@link String}。
     *
     * <p>启动自检必须逐行证明当前主密钥、该行的 {@code secretType/mailAccountId} AAD、
     * nonce 与密文是同一组。把这一操作留在加密组件内，避免自检层复制密码学参数；
     * 成功后的明文字节会立即清零，既不返回，也不记录。
     *
     * @throws SecretCipherException 主密钥不对、AAD 不对、nonce 非法或密文被篡改
     */
    public void verifyAuthenticity(
            SecretType secretType, Long mailAccountId, byte[] ciphertext, byte[] nonce) {
        byte[] plaintext = decryptBytes(secretType, mailAccountId, ciphertext, nonce);
        // doFinal 已完成 GCM tag 校验；这里有意不解释、更不返回明文。
        Arrays.fill(plaintext, (byte) 0);
    }

    private byte[] decryptBytes(
            SecretType secretType, Long mailAccountId, byte[] ciphertext, byte[] nonce) {
        try {
            Cipher cipher = newCipher(Cipher.DECRYPT_MODE, nonce, secretType, mailAccountId);
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException ex) {
            throw new SecretCipherException(
                    "解密 %s 凭据失败：主密钥是否更换过，或该密文是否属于其它账号"
                            .formatted(secretType == null ? "未知类型" : secretType.getValue()), ex);
        }
    }

    private Cipher newCipher(int mode, byte[] nonce, SecretType secretType, Long mailAccountId)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, masterKeyProvider.key(), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(associatedData(secretType, mailAccountId).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    /**
     * AAD 的拼接规则，<b>全项目只在这里定义一次</b>（{@code DATABASE.md} §8.2：
     * 不设 {@code associated_data} 列）。
     *
     * <p>null 的 id 显式写成字面 {@code "null"} 而不是依赖 Java 字符串拼接的隐式行为：
     * 后者看起来像疏漏，将来会有人「顺手修一下」。而改这个字符串等于让全部既有密文解不开，
     * 症状是「所有邮箱和 AI 突然一起连不上」——排查时不会有人怀疑一条拼接规则。
     * {@code SecretCipherTest} 用「换 AAD 必须解密失败」钉住它。
     */
    static String associatedData(SecretType secretType, Long mailAccountId) {
        return secretType.getValue() + ":"
               + (mailAccountId == null ? NO_MAIL_ACCOUNT : mailAccountId.toString());
    }

    /**
     * 一次加密的产物，两者必须一起入库：nonce 丢了密文就再也解不开。
     *
     * <p>nonce 不是秘密，明文入库即可；它只需要不重复。
     */
    public record EncryptedSecret(byte[] ciphertext, byte[] nonce) {
    }
}
