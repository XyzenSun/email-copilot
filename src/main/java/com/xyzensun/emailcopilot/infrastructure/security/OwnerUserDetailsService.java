package com.xyzensun.emailcopilot.infrastructure.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.OwnerCredential;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.OwnerCredentialMapper;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

/**
 * 从 {@code owner_credential} 加载登录凭据。
 *
 * <p>单用户系统只有一个账号，仍走标准 {@link UserDetailsService} 而不是自己比口令，
 * 图的是 {@code DaoAuthenticationProvider} 附带的两个保护：
 * <ul>
 *   <li>用户名不存在时它仍会跑一次哈希运算（{@code prepareTimingAttackProtection}）。
 *       否则「用户不存在」会明显更快返回，
 *       {@code INVALID_CREDENTIALS} 不区分两种失败的措施就被响应时间绕开了；</li>
 *   <li>{@code hideUserNotFoundExceptions} 默认开启，把
 *       {@link UsernameNotFoundException} 统一转成 {@code BadCredentialsException}。</li>
 * </ul>
 */
@Component
class OwnerUserDetailsService implements UserDetailsService {

    /** 单用户系统没有角色维度，但 {@link User} 要求至少一个权限。 */
    private static final String OWNER_AUTHORITY = "ROLE_OWNER";

    private final OwnerCredentialMapper ownerCredentialMapper;

    OwnerUserDetailsService(OwnerCredentialMapper ownerCredentialMapper) {
        this.ownerCredentialMapper = ownerCredentialMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        OwnerCredential credential = ownerCredentialMapper.selectOne(
                Wrappers.lambdaQuery(OwnerCredential.class).eq(OwnerCredential::getUsername, username));
        if (credential == null) {
            // 消息不进响应体：INVALID_CREDENTIALS 对外统一，避免枚举账号
            throw new UsernameNotFoundException("凭据不存在");
        }
        return User.withUsername(credential.getUsername())
                .password(credential.getPasswordHash())
                .authorities(OWNER_AUTHORITY)
                .build();
    }
}
