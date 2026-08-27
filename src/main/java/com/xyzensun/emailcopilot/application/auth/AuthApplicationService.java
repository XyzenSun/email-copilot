package com.xyzensun.emailcopilot.application.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.OwnerCredential;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.OwnerCredentialMapper;
import com.xyzensun.emailcopilot.interfaces.error.ApiError;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 登录、改口令与会话作废的编排（{@code API.md} §7）。
 *
 * <p>HTTP 会话的建立与销毁不在这里——那属于 {@code SessionController}。
 * 本服务只管「凭据对不对」「口令改不改」，以及改口令时把已有会话全部作废。
 */
@Service
public class AuthApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AuthApplicationService.class);

    private final AuthenticationManager authenticationManager;
    private final LoginAttemptGuard loginAttemptGuard;
    private final OwnerCredentialMapper ownerCredentialMapper;
    private final PasswordEncoder passwordEncoder;
    private final SessionRegistry sessionRegistry;

    public AuthApplicationService(
            AuthenticationManager authenticationManager,
            LoginAttemptGuard loginAttemptGuard,
            OwnerCredentialMapper ownerCredentialMapper,
            PasswordEncoder passwordEncoder,
            SessionRegistry sessionRegistry) {
        this.authenticationManager = authenticationManager;
        this.loginAttemptGuard = loginAttemptGuard;
        this.ownerCredentialMapper = ownerCredentialMapper;
        this.passwordEncoder = passwordEncoder;
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * 校验凭据。
     *
     * <p>限流检查在校验<b>之前</b>：锁定期间即使口令正确也必须拒绝，
     * 否则拿到正确口令的攻击者根本不受限流影响。
     *
     * @throws ApiException {@link ApiError#LOGIN_ATTEMPTS_EXCEEDED} 或
     *                      {@link ApiError#INVALID_CREDENTIALS}
     */
    public Authentication authenticate(String username, String password) {
        loginAttemptGuard.ensureNotLocked();
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, password));
            loginAttemptGuard.recordSuccess();
            return authentication;
        } catch (BadCredentialsException ex) {
            loginAttemptGuard.recordFailure();
            // 日志不记用户名也不记口令：单账号系统里用户名没有排查价值，
            // 而把调用方给的字符串写进日志会带来日志注入的口子
            log.warn("登录失败");
            // 不区分「用户名不存在」与「口令错误」，避免枚举账号
            throw new ApiException(ApiError.INVALID_CREDENTIALS);
        } catch (AuthenticationException ex) {
            // 数据库不可用、provider 配置损坏等不是“口令错误”，也不应累计失败次数。
            // 若一律吞成 INVALID_CREDENTIALS，数据库短暂故障会把所有者锁 15 分钟，
            // 用户还会被误导去反复改口令。异常对象只进服务端日志，响应不带 detail。
            log.error("登录认证基础设施失败", ex);
            throw new ApiException(ApiError.INTERNAL_ERROR);
        }
    }

    /**
     * 改口令，成功后<b>作废全部会话（含当前）</b>。
     *
     * <p>改密的动机往往正是怀疑口令泄露，保留当前会话等于给可能已在里面的人留门
     * （{@code DECISIONS.md} 213 行）。
     *
     * @throws ApiException {@link ApiError#INVALID_CREDENTIALS} 当前口令不对
     */
    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        OwnerCredential credential = ownerCredentialMapper.selectOne(
                Wrappers.lambdaQuery(OwnerCredential.class).eq(OwnerCredential::getUsername, username));
        if (credential == null || !passwordEncoder.matches(currentPassword, credential.getPasswordHash())) {
            throw new ApiException(ApiError.INVALID_CREDENTIALS);
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("改口令事务未启用同步回调");
        }

        String newPasswordHash = passwordEncoder.encode(newPassword);
        int updated = ownerCredentialMapper.updatePasswordIfUnchanged(
                username, credential.getPasswordHash(), newPasswordHash);
        if (updated == 0) {
            // 另一个改密请求已先提交；不能用本请求随后生成的哈希覆盖它
            throw new ApiException(ApiError.INVALID_CREDENTIALS);
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 必须在提交后作废：若事务最终回滚却先把 Session 全清掉，用户会被登出，
                // 但数据库里仍是旧口令；更重要的是并发登录只需在“登记后重读哈希”，
                // 就能和这个提交后作废组成无缝闭环
                expireAllSessions();
                log.info("登录口令已修改，全部会话已作废");
            }
        });
    }

    /**
     * 把 registry 里所有会话标记为过期。
     *
     * <p>{@code expireNow()} 只打标记，真正的销毁由 {@code ConcurrentSessionFilter}
     * 在该会话的下一个请求时完成（见 {@code SecurityConfig} 的 {@code sessionConcurrency}）。
     * 因此「作废」不是立即让内存里的 session 消失，而是让它下次再来时拿到 401。
     */
    private void expireAllSessions() {
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            List<SessionInformation> sessions = sessionRegistry.getAllSessions(principal, false);
            sessions.forEach(SessionInformation::expireNow);
        }
    }

    /**
     * Session 登记后再次确认口令仍是当前口令，并判定是否为默认口令。
     *
     * <p>为什么认证成功后还要确认一次：登录与改密可以并发。若旧口令刚校验成功、
     * Session 尚未登记时另一请求完成改密，改密那次遍历 {@link SessionRegistry} 看不到它，
     * 旧登录随后登记就会躲过“全部 Session 失效”。固定顺序为：
     * <pre>
     * 初次认证 → 登记 Session → 本方法重读当前哈希 → 保存 SecurityContext
     * </pre>
     * 改密发生在“登记”之前，本方法会发现旧口令已不匹配；发生在登记之后，
     * 改密会在 registry 中看见并作废这个 Session。两个方向都没有缝隙。
     *
     * <p>默认口令严格按 {@code API.md} §7.3 用
     * {@code PasswordEncoder.matches("admin123456", 库中哈希)} 判定一次。
     * 结果由调用方存进 session，<b>不加数据库列</b>，也不让每次
     * {@code GET /api/session} 都跑一次哈希。
     *
     * @throws ApiException {@link ApiError#INVALID_CREDENTIALS} 口令在并发窗口中已被改掉
     */
    public boolean confirmCurrentPasswordAndCheckDefault(String username, String submittedPassword) {
        OwnerCredential credential = ownerCredentialMapper.selectOne(
                Wrappers.lambdaQuery(OwnerCredential.class).eq(OwnerCredential::getUsername, username));
        if (credential == null) {
            // 认证刚刚成功却找不到同一个账号，说明凭据行在两个查询之间被异常删除。
            throw new IllegalStateException("认证成功后登录凭据行不存在");
        }
        if (!passwordEncoder.matches(submittedPassword, credential.getPasswordHash())) {
            throw new ApiException(ApiError.INVALID_CREDENTIALS);
        }
        return passwordEncoder.matches(DefaultOwnerCredential.PASSWORD, credential.getPasswordHash());
    }
}
