package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xyzensun.emailcopilot.domain.enums.SecretType;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.ExternalAccountSecret;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 外部凭据，AES-GCM 密文。唯一索引带 NULLS NOT DISTINCT。
 */
public interface ExternalAccountSecretMapper extends BaseMapper<ExternalAccountSecret> {

    /**
     * 写入或覆盖一条凭据密文。
     *
     * <p><b>靠唯一约束做幂等，不写「先查再插」</b>（{@code DATABASE.md} §1 第 4 条）：
     * 先查再插在并发下会两个都查到「不存在」然后都插入，靠约束才是真的只留一条。
     *
     * <p>{@code on conflict (mail_account_id, secret_type)} 的列推断能匹配到带
     * {@code nulls not distinct} 的 {@code uk_external_account_secret}（已在 PostgreSQL 18 实测），
     * 因此 AI API key 那条 {@code (null, 'ai_api_key')} 也会走 update 而不是插出第二行。
     *
     * <p>{@code updated_at} 由数据库的 {@code now()} 填：注解 SQL 不经过
     * {@code TimestampAutoFillHandler}，漏了它会撞上 {@code not null}。
     */
    @Insert("""
            insert into external_account_secret (mail_account_id, secret_type, ciphertext, nonce, updated_at)
            values (#{mailAccountId}, #{secretType}, #{ciphertext}, #{nonce}, now())
            on conflict (mail_account_id, secret_type)
            do update set ciphertext = excluded.ciphertext,
                          nonce      = excluded.nonce,
                          updated_at = now()
            """)
    int upsert(@Param("mailAccountId") Long mailAccountId,
               @Param("secretType") SecretType secretType,
               @Param("ciphertext") byte[] ciphertext,
               @Param("nonce") byte[] nonce);
}
