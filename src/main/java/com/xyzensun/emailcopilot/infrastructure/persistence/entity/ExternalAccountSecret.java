package com.xyzensun.emailcopilot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xyzensun.emailcopilot.domain.enums.SecretType;
import lombok.Data;
import lombok.ToString;

import java.time.OffsetDateTime;

/**
 * 外部凭据，AES-GCM 可逆加密保存（{@code DATABASE.md} §8.2）。
 *
 * <p>密钥由环境变量注入，<b>既不入仓也不入库</b>——因此仅取得数据库副本无法解出外部凭据，
 * 需要服务器环境与数据库同时失守。
 *
 * <p><b>库中已有密文时，密钥缺失或无法逐行通过 AES-GCM 认证都必须启动失败</b>，
 * 不允许静默生成或接受错误密钥——那会让既有凭据直到真正使用时才暴露为不可解密。
 *
 * <p><b>唯一索引必须写 {@code NULLS NOT DISTINCT}</b>：AI API key 不绑定邮箱账号，
 * {@link #mailAccountId} 为 null，而 PostgreSQL 默认语义下 NULL 彼此不相等，
 * 漏掉该子句能插入任意多条 {@code (NULL, ai_api_key)}，取用时无法确定该用哪条——
 * <b>而且不报错</b>。
 *
 * <p><b>不设 associatedData 列</b>：AES-GCM 的 AAD 由代码按固定规则拼出
 * （{@code secretType + ':' + mailAccountId}），一处定义。存一份的唯一后果是
 * 它与解密时重算的值若有任何差异，解密直接失败且极难排查。
 *
 * <p>不设 DEK、信封加密、KMS、轮换或备份恢复机制。
 *
 * <p><b>凭据永不回显</b>：不给明文、不给掩码（{@code ****1234} 泄露长度与尾部）、
 * 不给长度；接口只返回是否已配置。
 */
@Data
@TableName("external_account_secret")
public class ExternalAccountSecret {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 逻辑引用 mail_account；AI API key 不绑定账号时为 null。 */
    private Long mailAccountId;

    private SecretType secretType;

    /**
     * AES-GCM 密文。
     *
     * <p>{@code @ToString.Exclude}：Lombok 的 {@code @Data} 会把 byte[] 用
     * {@code Arrays.toString} 展开成一串字节数值塞进 toString()。虽然是密文不是明文，
     * 但凭据相关数据一律不进日志。
     */
    @ToString.Exclude
    private byte[] ciphertext;

    /** GCM nonce，{@code GCMParameterSpec} 需显式传入。 */
    @ToString.Exclude
    private byte[] nonce;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
