package com.xyzensun.emailcopilot.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;

import java.util.List;

/**
 * 安全装配（{@code ARCHITECTURE.md} §7）。
 *
 * <p>形态是<b>服务端 Cookie Session + CSRF 双提交</b>，不引入 JWT、refresh token、
 * CORS credentials 或客户端 token 存储（{@code DECISIONS.md} 209 / 214 行）。
 * 会话存应用内存，重启即要求重新登录。
 *
 * <p><b>登录不走 {@code formLogin}</b>，由 {@code SessionController} 手工调
 * {@link AuthenticationManager}：契约是 {@code POST /api/session} 收 JSON、返回
 * {@code SessionInfo}（含 {@code usingDefaultPassword}），而 formLogin 是表单 + 重定向语义。
 * 代价是认证 filter 原本代劳的三件事必须自己调，见 {@link #sessionAuthenticationStrategy}
 * 与 {@link #securityContextRepository} 的说明。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * {@code DelegatingPasswordEncoder}：编码时用当前默认算法并写入 {@code {bcrypt}} 前缀，
     * 校验时按前缀选算法。
     *
     * <p>这个前缀就是 {@code DATABASE.md} §8.1 里「不设 algorithm 列」的原因——
     * 算法标识已经在编码字符串里，将来换 Argon2 时旧口令仍可识别，
     * 不需要迁移也不需要额外一列。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    /**
     * 登录会话的登记处，改口令时靠它把<b>全部</b> session 作废。
     *
     * <p>必须同时有 {@link #httpSessionEventPublisher()}：没有它，session 正常销毁的事件
     * 到不了 {@link SessionRegistryImpl}，registry 里会累积已经不存在的条目，
     * 后续 {@code expireNow()} 打在空气上。
     */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /**
     * 手工登录时必须显式调用的会话策略，两个组成部分各自补回一件认证 filter 原本代劳的事。
     *
     * <table border="1">
     *   <caption>漏掉任一环的后果</caption>
     *   <tr><th>组成</th><th>作用</th><th>漏掉会怎样</th></tr>
     *   <tr><td>{@link ChangeSessionIdAuthenticationStrategy}</td><td>登录后换 Session ID</td>
     *       <td>会话固定攻击可用：攻击者预先诱导受害者带上自己知道的 session id，
     *           受害者登录后那个 id 直接变成已登录会话</td></tr>
     *   <tr><td>{@link RegisterSessionAuthenticationStrategy}</td><td>把 session 登记进 registry</td>
     *       <td>改口令时 registry 里没有这个 session，「全部 session 失效」漏掉它——
     *           而改密的动机往往正是怀疑口令泄露，用户以为清干净了，实际那个会话还活着</td></tr>
     * </table>
     */
    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy(SessionRegistry sessionRegistry) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new RegisterSessionAuthenticationStrategy(sessionRegistry)));
    }

    /**
     * 与 Spring Security 默认一致的组合：先 request attribute（同请求内传递），
     * 再 HttpSession（跨请求持久）。
     *
     * <p>显式声明成 bean 是为了让手工登录时的 {@code saveContext} 与 filter 链读取
     * 用的是<b>同一个</b>实现——两边不一致的话，登录接口返回 200 而下一个请求仍是未登录，
     * 而两处代码单看都对。
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SessionRegistry sessionRegistry,
            SecurityContextRepository securityContextRepository,
            ProblemDetailSecurityOutcomes.UnauthenticatedEntryPoint unauthenticatedEntryPoint,
            ProblemDetailSecurityOutcomes.CsrfAccessDeniedHandler csrfAccessDeniedHandler,
            ProblemDetailSecurityOutcomes.ExpiredSessionStrategy expiredSessionStrategy) throws Exception {

        http
                .authorizeHttpRequests(authorize -> authorize
                        // 查登录态与登录本身必须匿名可达（API.md §7）
                        .requestMatchers(HttpMethod.GET, "/api/session").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/session").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        // 非 /api 的是前端静态资源与 SPA 路由（阶段 9，同源部署）。
                        // 登录页本身必须能在未登录时加载，鉴权由上面的 /api/** 承担
                        .anyRequest().permitAll())

                .csrf(csrf -> csrf
                        // withHttpOnlyFalse：cookie 必须能被前端 JS 读到才能放进请求头
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                // 惰性 token 不主动碰就不写 cookie，见 CsrfCookieFilter
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)

                .securityContext(context -> context.securityContextRepository(securityContextRepository))

                .sessionManagement(session -> session
                        .sessionFixation(SessionManagementConfigurer.SessionFixationConfigurer::changeSessionId)
                        // maximumSessions(-1) = 不限并发数量。配它的真正目的是让
                        // ConcurrentSessionFilter 上链——那个 filter 才是「被 expireNow 标记过的
                        // session 下次请求时被销毁」的执行者。不配 sessionConcurrency 的话
                        // 改口令后作废全部 session 这件事会静默失效
                        .sessionConcurrency(concurrency -> concurrency
                                .maximumSessions(-1)
                                .sessionRegistry(sessionRegistry)
                                .expiredSessionStrategy(expiredSessionStrategy)))

                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(unauthenticatedEntryPoint)
                        .accessDeniedHandler(csrfAccessDeniedHandler))

                // 关掉「登录后跳回原请求」的缓存。SPA 不需要它（前端自己记路由），
                // 而它的默认实现把被拦下的请求存进 HttpSession —— 也就是每个未登录请求
                // 都创建一个会话。公网扫描流量会因此在内存里堆出大量空会话，
                // 而会话存内存、要等超时才回收
                .requestCache(RequestCacheConfigurer::disable)

                // 登录、登出都由 SessionController 承担（DELETE /api/session），
                // 表单登录与 HTTP Basic 都不是本项目的契约
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);

        return http.build();
    }
}
