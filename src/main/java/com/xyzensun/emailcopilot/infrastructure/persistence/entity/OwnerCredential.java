package com.xyzensun.emailcopilot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;

import java.time.OffsetDateTime;

/**
 * 登录凭据，单用户系统通常一行（{@code DATABASE.md} §8.1）。
 *
 * <p><b>不设 salt 与 algorithm 两列</b>：现代密码哈希的输出字符串本身已内嵌盐与算法参数。
 * <pre>
 * {bcrypt}$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
 *  └算法┘ └版本┘└cost┘└──────盐 22 字符──────┘└────────哈希────────┘
 * </pre>
 * {@code PasswordEncoder} 的接口是 {@code encode(明文)} → 字符串、
 * {@code matches(明文, 编码串)} → boolean，<b>从不单独传盐</b>；
 * 另存一列 salt 没有任何代码会读，反而会诱导实现绕过标准编码器手工拼盐。
 * {@code {bcrypt}} 前缀由 {@code DelegatingPasswordEncoder} 写入、已承担算法标识，
 * 将来换 Argon2 时旧口令仍可识别。
 *
 * <p><b>登录失败限流在内存中做，不落库</b>：重启清零可接受，不值得为它建表。
 *
 * <p>与 {@link ExternalAccountSecret} 的处理方式<b>不可互换</b>：
 * 这里是加盐哈希、不可逆，仅用于登录校验；那边是对称加密、可逆，
 * 运行时还原后连接外部服务。
 */
@Data
@TableName("owner_credential")
public class OwnerCredential {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /**
     * {@code PasswordEncoder} 的完整编码字符串。
     *
     * <p>{@code @ToString.Exclude}：哈希虽不可逆，仍不得进日志——
     * 拿到它就能离线爆破，而单用户系统的默认口令是已知的。
     */
    @ToString.Exclude
    private String passwordHash;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
