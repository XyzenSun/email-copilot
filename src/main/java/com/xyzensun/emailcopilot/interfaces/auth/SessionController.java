package com.xyzensun.emailcopilot.interfaces.auth;

import com.xyzensun.emailcopilot.application.auth.AuthApplicationService;
import com.xyzensun.emailcopilot.interfaces.auth.dto.LoginRequest;
import com.xyzensun.emailcopilot.interfaces.auth.dto.SessionInfoResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录态资源（{@code API.md} §7）：查 / 建 / 删对应 GET / POST / DELETE。
 *
 * <p>把登录态表达成一个资源，OpenAPI 里就只有一个路径（{@code DECISIONS.md} 210 行）。
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    /** 存在 session 里的属性名，{@code GET /api/session} 从这里读。 */
    static final String USING_DEFAULT_PASSWORD_ATTRIBUTE = "usingDefaultPassword";

    private final AuthApplicationService authApplicationService;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;

    public SessionController(AuthApplicationService authApplicationService,
                             SessionAuthenticationStrategy sessionAuthenticationStrategy,
                             SecurityContextRepository securityContextRepository) {
        this.authApplicationService = authApplicationService;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityContextRepository = securityContextRepository;
    }

    /**
     * 查登录态，顺带下发 CSRF cookie（cookie 由 {@code CsrfCookieFilter} 写）。
     *
     * <p><b>未登录也返回 200</b>（{@code API.md} §7.1）：本接口的语义是「查询登录状态」，
     * 未登录是一个合法答案。若返回 401，前端每次启动都会先在 devtools 里红一个错误，
     * 且要在 error 分支里区分「正常未登录」与「真的出错」。
     *
     * <p>用 {@code request.getSession(false)} 而不是注入 {@code HttpSession}：
     * 后者会为每个未登录请求<b>创建</b>一个 session。公网上的扫描流量会因此在内存里
     * 堆出大量空会话，而会话存内存、直到超时才回收。
     */
    @GetMapping
    public SessionInfoResponse getSession(@AuthenticationPrincipal UserDetails owner,
                                         HttpServletRequest request) {
        if (owner == null) {
            return SessionInfoResponse.anonymous();
        }
        HttpSession session = request.getSession(false);
        boolean usingDefaultPassword = session != null
                && Boolean.TRUE.equals(session.getAttribute(USING_DEFAULT_PASSWORD_ATTRIBUTE));
        return SessionInfoResponse.authenticated(owner.getUsername(), usingDefaultPassword);
    }

    /**
     * 登录。
     *
     * <p>五步顺序都不能省，第二步是认证 filter 原本代劳、手工登录必须自己补的
     * （理由见 {@code SecurityConfig#sessionAuthenticationStrategy}）：
     * <ol>
     *   <li>{@code getSession()} 确保有 session ——
     *       没有 session 时换 id 无从下手，而客户端<b>可能</b>带来一个 id，
     *       正是那种情况需要被换掉；</li>
     *   <li>{@code onAuthentication} 换 Session ID + 登记进 {@code SessionRegistry}；</li>
     *   <li>登记后重读口令哈希，封住“旧口令认证成功但尚未登记时并发改密”的窗口；</li>
     *   <li>{@code saveContext} 把认证结果写进新 session ——
     *       漏掉它，登录返回 200 而下一个请求仍是未登录；</li>
     *   <li>写 {@code usingDefaultPassword} 属性，供后续 {@code GET} 读取。</li>
     * </ol>
     */
    @PostMapping
    public SessionInfoResponse login(@Valid @RequestBody LoginRequest loginRequest,
                                     HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication =
                authApplicationService.authenticate(loginRequest.username(), loginRequest.password());

        request.getSession();
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);

        boolean usingDefaultPassword;
        try {
            usingDefaultPassword = authApplicationService.confirmCurrentPasswordAndCheckDefault(
                    authentication.getName(), loginRequest.password());
        } catch (RuntimeException ex) {
            // Session 已登记但尚未保存登录态；重新确认失败时必须撤销登记并销毁它，
            // 否则一次失败登录会在 registry 里留下永不使用的活跃 Session
            new SecurityContextLogoutHandler().logout(request, response, authentication);
            throw ex;
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        request.getSession().setAttribute(USING_DEFAULT_PASSWORD_ATTRIBUTE, usingDefaultPassword);

        return SessionInfoResponse.authenticated(authentication.getName(), usingDefaultPassword);
    }

    /** 登出：销毁 session 并清空 {@code SecurityContext}。 */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(
                request, response, SecurityContextHolder.getContext().getAuthentication());
    }
}
