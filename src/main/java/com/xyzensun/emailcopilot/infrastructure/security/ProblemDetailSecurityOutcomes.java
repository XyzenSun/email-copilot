package com.xyzensun.emailcopilot.infrastructure.security;

import com.xyzensun.emailcopilot.interfaces.error.ApiError;
import com.xyzensun.emailcopilot.interfaces.error.ProblemDetailFactory;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.session.SessionInformationExpiredEvent;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Spring Security 三个<b>不经过 Controller</b> 的出口，统一吐 RFC 9457 Problem Details。
 *
 * <p>这三条路径由 filter 链直接结束请求，{@code @RestControllerAdvice} 管不到它们。
 * 默认实现返回的是 Spring Security 自己的错误页或纯文本，没有 {@code code} 字段——
 * 而前端<b>只据 {@code code} 分支</b>。少了它，未登录时前端只能靠状态码猜，
 * 一旦某个出口的形状与其它不同，就会出现「明明该跳登录页却卡在原地」这类问题。
 *
 * <p>三个内部类合在一个文件里，是因为它们共用同一个 {@link ProblemDetailFactory}
 * 且语义成组——分散到三个文件反而看不出「这三处必须一致」。
 */
final class ProblemDetailSecurityOutcomes {

    private ProblemDetailSecurityOutcomes() {
    }

    /** 未登录访问受保护资源 → 401 {@link ApiError#AUTHENTICATION_REQUIRED}。 */
    @Component
    static class UnauthenticatedEntryPoint implements AuthenticationEntryPoint {

        private final ProblemDetailFactory problemDetailFactory;

        UnauthenticatedEntryPoint(ProblemDetailFactory problemDetailFactory) {
            this.problemDetailFactory = problemDetailFactory;
        }

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                             AuthenticationException authException) throws IOException {
            // detail 保持为 null：认证失败的具体原因不该告诉未认证的调用方
            problemDetailFactory.write(request, response, ApiError.AUTHENTICATION_REQUIRED, null);
        }
    }

    /**
     * CSRF 缺失或不匹配 → 403 {@link ApiError#CSRF_TOKEN_INVALID}。
     *
     * <p>本项目单用户、无角色维度，{@code /api/**} 只有「已登录 / 未登录」两态，
     * 因此走到 {@code AccessDeniedHandler} 的实际只有 CSRF 一种，直接映射。
     * <b>将来若引入权限维度，这里必须按异常类型分流</b>，否则「无权限」会被误报成
     * 「CSRF 令牌无效」，前端照着刷新 token 重试，永远试不通。
     */
    @Component
    static class CsrfAccessDeniedHandler implements AccessDeniedHandler {

        private final ProblemDetailFactory problemDetailFactory;

        CsrfAccessDeniedHandler(ProblemDetailFactory problemDetailFactory) {
            this.problemDetailFactory = problemDetailFactory;
        }

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                           AccessDeniedException accessDeniedException) throws IOException {
            problemDetailFactory.write(request, response, ApiError.CSRF_TOKEN_INVALID, null);
        }
    }

    /**
     * 会话被作废（改口令后）之后再来请求：受保护接口返回 401，
     * 但 {@code GET /api/session} 继续进入 Controller、返回 200 + 未登录状态。
     *
     * <p>后一个分支不是特例放宽鉴权，而是严格遵守 {@code API.md} §7.1：
     * {@code GET /api/session} 的语义是“查询登录状态”，未登录（包括刚失效）是一个合法答案，
     * {@code openapi.yaml} 也只为它声明了 200。若所有过期 Session 都统一短路成 401，
     * 用户改密后刷新登录页的第一件事就与契约不符。
     *
     * <p>{@code ConcurrentSessionFilter} 调到这里之前已经执行
     * {@code SecurityContextLogoutHandler}：旧 HttpSession 已销毁、SecurityContext 已清空。
     * 因此对这个只读端点继续 filter chain 是安全的，Controller 只会看到匿名用户。
     * 其它接口仍返回统一的 RFC 9457 Problem Details；默认策略的一句纯文本不能用，
     * 否则前端会把它当 JSON 解析而失败，表现为“页面卡住”而不是跳登录页。
     */
    @Component
    static class ExpiredSessionStrategy implements SessionInformationExpiredStrategy {

        private static final String SESSION_ENDPOINT = "/api/session";

        private final ProblemDetailFactory problemDetailFactory;

        ExpiredSessionStrategy(ProblemDetailFactory problemDetailFactory) {
            this.problemDetailFactory = problemDetailFactory;
        }

        @Override
        public void onExpiredSessionDetected(SessionInformationExpiredEvent event)
                throws IOException, ServletException {
            HttpServletRequest request = event.getRequest();
            if (HttpMethod.GET.matches(request.getMethod())
                    && SESSION_ENDPOINT.equals(request.getRequestURI())) {
                event.getFilterChain().doFilter(request, event.getResponse());
                return;
            }
            problemDetailFactory.write(request, event.getResponse(),
                    ApiError.AUTHENTICATION_REQUIRED, "会话已失效，请重新登录");
        }
    }
}
