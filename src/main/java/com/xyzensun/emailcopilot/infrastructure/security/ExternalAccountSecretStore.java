package com.xyzensun.emailcopilot.infrastructure.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xyzensun.emailcopilot.domain.enums.SecretType;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.ExternalAccountSecret;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.ExternalAccountSecretMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 外部凭据的安全存取：<b>明文进、明文出，加密对调用方不可见</b>。
 *
 * <p>调用方（邮箱账号设置、AI 连接配置）只管给明文口令与取回明文口令，
 * 不需要知道 AAD 怎么拼、nonce 存在哪。把这层封起来是为了让「AAD 一处定义」
 * 这条约束真的只有一处——散在各个业务服务里迟早会有一处拼错，
 * 而那一处的现象是「这个账号的口令存进去就再也读不出来」。
 *
 * <p><b>不提供「删除单条凭据」</b>（{@code DECISIONS.md} 221 行）：
 * 停用通道用 {@code imapEnabled: false}，删账号时凭据连带删除。
 * 单独删凭据只会造出「启用但无口令」的状态。
 */
@Component
public class ExternalAccountSecretStore {

    private final ExternalAccountSecretMapper secretMapper;
    private final SecretCipher secretCipher;

    public ExternalAccountSecretStore(ExternalAccountSecretMapper secretMapper, SecretCipher secretCipher) {
        this.secretMapper = secretMapper;
        this.secretCipher = secretCipher;
    }

    /**
     * 写入或覆盖，<b>不区分首次配置与修改</b>（{@code DECISIONS.md} 220 行）：
     * 邮箱口令会过期、会换应用专用密码，要求「先删再建」会留下一个连不上邮箱的中间状态。
     *
     * @param mailAccountId AI API key 时为 null
     */
    public void save(SecretType secretType, Long mailAccountId, String plaintext) {
        validateOwner(secretType, mailAccountId);
        SecretCipher.EncryptedSecret encrypted = secretCipher.encrypt(secretType, mailAccountId, plaintext);
        secretMapper.upsert(mailAccountId, secretType, encrypted.ciphertext(), encrypted.nonce());
    }

    /**
     * @return 明文；未配置时 {@link Optional#empty()}
     * @throws SecretCipherException 主密钥更换过、或密文被搬到了别的账号下
     */
    public Optional<String> load(SecretType secretType, Long mailAccountId) {
        validateOwner(secretType, mailAccountId);
        ExternalAccountSecret secret = secretMapper.selectOne(queryFor(secretType, mailAccountId));
        return Optional.ofNullable(secret)
                .map(row -> secretCipher.decrypt(secretType, mailAccountId, row.getCiphertext(), row.getNonce()));
    }

    /**
     * 是否已配置。<b>接口只返回这个布尔，永不回显凭据</b>——
     * 不给明文、不给掩码（{@code ****1234} 泄露长度与尾部字符）、也不给长度。
     */
    public boolean exists(SecretType secretType, Long mailAccountId) {
        validateOwner(secretType, mailAccountId);
        return secretMapper.selectCount(queryFor(secretType, mailAccountId)) > 0;
    }

    /**
     * 强制 {@code DATABASE.md} §8.2 的归属规则：AI API key、Exa MCP key 与 Tavily key 是应用级凭据、
     * 必须不绑定账号；IMAP / SMTP 凭据必须属于一个邮箱账号。
     *
     * <p>数据库只把 {@code mail_account_id} 定义成 nullable，无法表达这个跨列蕴含关系，
     * 因此由 Java 维护。若放任错误组合入库，最坏结果不是一条“多余数据”：
     * 阶段 3 读取 AI key 时固定查 {@code mail_account_id IS NULL}，绑定到某账号的 key
     * 会在库里明明存在、界面却一直显示“AI 尚未配置”；反过来，不绑定账号的 IMAP 口令
     * 永远没有任何邮箱会读到。
     */
    private static void validateOwner(SecretType secretType, Long mailAccountId) {
        if (isGlobalSecret(secretType) && mailAccountId != null) {
            throw new IllegalArgumentException(secretType.getValue() + " 不得绑定邮箱账号");
        }
        if (!isGlobalSecret(secretType) && mailAccountId == null) {
            throw new IllegalArgumentException(secretType.getValue() + " 必须绑定邮箱账号");
        }
    }

    /**
     * 全局凭据（{@code mail_account_id} 为 null）：AI API key、Exa MCP key 与 Tavily key。
     * 三者都是应用级凭据，不归属任何邮箱账号。
     */
    private static boolean isGlobalSecret(SecretType secretType) {
        return secretType == SecretType.AI_API_KEY
                || secretType == SecretType.EXA_API_KEY
                || secretType == SecretType.TAVILY_API_KEY;
    }

    /**
     * <b>{@code mailAccountId} 为 null 时必须用 {@code isNull} 而不是 {@code eq}。</b>
     *
     * <p>{@code eq(column, null)} 会照原样生成 {@code column = ?} 并绑定 null，
     * 而 SQL 里 {@code x = null} 恒为 unknown、永远匹配不到任何行。
     * 这条路径正是 AI API key（不绑定邮箱账号，id 为 null），
     * 于是症状会是「AI key 在界面上明明填过、系统却始终说没配置」，
     * 而写入那一侧完全正常、日志里也没有任何异常。
     */
    private static LambdaQueryWrapper<ExternalAccountSecret> queryFor(SecretType secretType, Long mailAccountId) {
        LambdaQueryWrapper<ExternalAccountSecret> query = Wrappers.lambdaQuery(ExternalAccountSecret.class)
                .eq(ExternalAccountSecret::getSecretType, secretType);
        return mailAccountId == null
                ? query.isNull(ExternalAccountSecret::getMailAccountId)
                : query.eq(ExternalAccountSecret::getMailAccountId, mailAccountId);
    }
}
