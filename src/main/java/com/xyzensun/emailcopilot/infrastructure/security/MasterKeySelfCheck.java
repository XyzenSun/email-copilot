package com.xyzensun.emailcopilot.infrastructure.security;

import com.xyzensun.emailcopilot.infrastructure.persistence.entity.ExternalAccountSecret;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.ExternalAccountSecretMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * 启动时交叉校验主密钥与数据库中的每一行外部凭据密文（{@code ARCHITECTURE.md} §7.2）。
 *
 * <table border="1">
 *   <caption>主密钥与密文的启动处置</caption>
 *   <tr><th>主密钥</th><th>库中密文</th><th>行为</th></tr>
 *   <tr><td>合法</td><td>全部可认证解密</td><td>正常启动</td></tr>
 *   <tr><td>合法</td><td>任一行不可认证解密</td><td><b>启动失败</b></td></tr>
 *   <tr><td>缺失</td><td>&gt; 0 行</td><td><b>启动失败</b></td></tr>
 *   <tr><td>缺失</td><td>0 行</td><td>启动 + WARN，加密能力不可用</td></tr>
 *   <tr><td>格式非法</td><td>任意</td><td><b>启动失败</b>（在 {@link MasterKeyProvider} 构造时）</td></tr>
 * </table>
 *
 * <p><b>为什么 key 已配置也必须实际解密</b>：AES 密钥没有可与之比较的数据库指纹，
 * “格式是 32 字节”只证明它能用于 AES，不证明它是加密既有凭据的那一把。
 * 错误 key、被搬到其它账号/类型下的密文或被篡改的 tag 都只有在按每行
 * {@code secret_type + ':' + mail_account_id} 作为 AAD 执行 GCM 认证时才能发现。
 * 若带病启动，真正用凭据时只会表现为邮箱认证或 AI 调用失败，定位已经太晚。
 *
 * <p>时机必须在 Flyway 建表之后，因此标 {@link DependsOnDatabaseInitialization}；
 * 同时用 {@link InitializingBean} 而不是 {@code ApplicationReadyEvent}，让失败发生在容器
 * refresh 阶段，而不是 Web 端口已开始监听之后。
 */
@Component
@DependsOnDatabaseInitialization
class MasterKeySelfCheck implements InitializingBean, MasterKeyStatusSource {

    private static final Logger log = LoggerFactory.getLogger(MasterKeySelfCheck.class);
    private static final String ENV_NAME = "EMAIL_COPILOT_MASTER_KEY";

    private final MasterKeyProvider masterKeyProvider;
    private final ExternalAccountSecretMapper externalAccountSecretMapper;
    private final SecretCipher secretCipher;

    /** 只有 {@link #afterPropertiesSet()} 成功完成后才发布。 */
    private volatile MasterKeyStatus verifiedStatus;

    @Autowired
    MasterKeySelfCheck(
            MasterKeyProvider masterKeyProvider,
            ExternalAccountSecretMapper externalAccountSecretMapper,
            SecretCipher secretCipher) {
        this.masterKeyProvider = masterKeyProvider;
        this.externalAccountSecretMapper = externalAccountSecretMapper;
        this.secretCipher = secretCipher;
    }

    /**
     * 保留阶段 2 的包内构造入口，避免基础设施单元测试因新增校验依赖而失去源代码兼容性。
     * Spring 生产 bean 使用上面的显式 {@link SecretCipher} 构造器。
     */
    MasterKeySelfCheck(
            MasterKeyProvider masterKeyProvider,
            ExternalAccountSecretMapper externalAccountSecretMapper) {
        this(masterKeyProvider, externalAccountSecretMapper, new SecretCipher(masterKeyProvider));
    }

    @Override
    public void afterPropertiesSet() {
        if (!masterKeyProvider.isConfigured()) {
            verifyMissingKeyState();
            return;
        }
        verifyAllCiphertexts();
    }

    /**
     * 设置应用层只能读取这份启动时确认过的快照，不接触 provider、密文或解密结果。
     */
    @Override
    public MasterKeyStatus getMasterKeyStatus() {
        MasterKeyStatus status = verifiedStatus;
        if (status == null) {
            throw new IllegalStateException("主密钥启动自检尚未完成");
        }
        return status;
    }

    private void verifyMissingKeyState() {
        // 缺 key 时不加载密文，计数足以决定“允许全新库启动”还是 fail-fast。
        long ciphertextCount = externalAccountSecretMapper.selectCount(null);
        if (ciphertextCount > 0) {
            throw new MasterKeyException(
                    ("拒绝启动：数据库中已有 %d 条外部凭据密文，但未配置主密钥"
                     + "（环境变量 %s）。"
                     + "此时生成新密钥会让这些凭据永久无法解密，因此不做任何自动处理——"
                     + "请注入原来那把密钥后重启").formatted(ciphertextCount, ENV_NAME));
        }
        // 空集合不存在“不匹配”的密文；matches=true 与 OpenAPI 的 fail-fast 语义一致。
        verifiedStatus = new MasterKeyStatus(false, true);
        log.warn("未配置主密钥（环境变量 {}），"
                 + "外部凭据加密不可用；配置邮箱账号或 AI API key 前必须先注入它", ENV_NAME);
    }

    private void verifyAllCiphertexts() {
        List<ExternalAccountSecret> ciphertextRows = externalAccountSecretMapper.selectList(null);
        try {
            for (ExternalAccountSecret ciphertextRow : ciphertextRows) {
                secretCipher.verifyAuthenticity(
                        ciphertextRow.getSecretType(),
                        ciphertextRow.getMailAccountId(),
                        ciphertextRow.getCiphertext(),
                        ciphertextRow.getNonce());
            }
        } catch (SecretCipherException ex) {
            // 不指出失败行、类型、账号、密文值或实际长度；启动日志只保留可行动的安全摘要。
            throw new MasterKeyException(
                    "拒绝启动：当前主密钥无法认证数据库中的外部凭据密文。"
                    + "主密钥可能已更换，或密文完整性已受损；请恢复原主密钥或数据库备份后重启",
                    ex);
        }
        verifiedStatus = new MasterKeyStatus(true, true);
        log.info("外部凭据主密钥已配置，已有密文完整性验证通过");
    }

    /**
     * 保留阶段 2 的纯判定测试入口。它只表达“缺 key”分支；生产启动不能用它替代
     * {@link #verifyAllCiphertexts()}，因为 key 已配置时仍必须逐行执行 AES-GCM 认证。
     */
    static void verify(boolean masterKeyConfigured, LongSupplier ciphertextCount) {
        if (masterKeyConfigured) {
            return;
        }
        long count = ciphertextCount.getAsLong();
        if (count > 0) {
            throw new MasterKeyException(
                    ("拒绝启动：数据库中已有 %d 条外部凭据密文，但未配置主密钥"
                     + "（环境变量 %s）。"
                     + "此时生成新密钥会让这些凭据永久无法解密，因此不做任何自动处理——"
                     + "请注入原来那把密钥后重启").formatted(count, ENV_NAME));
        }
    }
}
