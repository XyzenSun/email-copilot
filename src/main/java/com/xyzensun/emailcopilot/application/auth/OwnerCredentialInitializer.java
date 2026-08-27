package com.xyzensun.emailcopilot.application.auth;

import com.xyzensun.emailcopilot.infrastructure.persistence.entity.OwnerCredential;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.OwnerCredentialMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首次启动时创建默认登录账号（{@code API.md} §7.3）。
 *
 * <p>口令<b>当场哈希后入库，明文不落库</b>。
 *
 * <p>标 {@link DependsOnDatabaseInitialization} 是因为要在 Flyway 建表之后才能查
 * {@code owner_credential}；用 {@link InitializingBean} 而非 {@code ApplicationReadyEvent}
 * 是为了在开始对外服务之前就把账号准备好——否则存在一个「端口已监听但还没有任何账号」
 * 的窗口，那期间的登录请求会失败。
 */
@Component
@DependsOnDatabaseInitialization
class OwnerCredentialInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(OwnerCredentialInitializer.class);

    private final OwnerCredentialMapper ownerCredentialMapper;
    private final PasswordEncoder passwordEncoder;

    OwnerCredentialInitializer(OwnerCredentialMapper ownerCredentialMapper, PasswordEncoder passwordEncoder) {
        this.ownerCredentialMapper = ownerCredentialMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void afterPropertiesSet() {
        // null 条件即全表计数。表非空就什么都不做——改过口令的库不能被重置回默认值
        if (ownerCredentialMapper.selectCount(null) > 0) {
            return;
        }

        OwnerCredential credential = new OwnerCredential();
        credential.setUsername(DefaultOwnerCredential.USERNAME);
        credential.setPasswordHash(passwordEncoder.encode(DefaultOwnerCredential.PASSWORD));
        ownerCredentialMapper.insert(credential);

        log.warn("已创建默认登录账号 {}（默认口令）。把服务暴露到公网前请先改口令——"
                 + "默认凭据未改时，任何扫描器都能登录并取得全部邮箱凭据与发信能力",
                DefaultOwnerCredential.USERNAME);
    }
}
