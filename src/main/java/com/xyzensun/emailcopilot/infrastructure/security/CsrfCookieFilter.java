package com.xyzensun.emailcopilot.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 强制渲染 CSRF token，从而把它写进 {@code XSRF-TOKEN} cookie。
 *
 * <p>Spring Security 6+ 的 CSRF token 是<b>惰性</b>的：没有任何代码碰过它，
 * {@code saveToken} 就不会被调用，响应里也就没有 {@code Set-Cookie: XSRF-TOKEN}。
 *
 * <p>而契约要求 {@code GET /api/session} 顺带下发 token（{@code API.md} §7.2：
 * 不设单独的取 token 端点）。少了这个 filter，前端启动后拿不到 token，
 * 此后每个写操作都 403——<b>包括登录本身</b>。
 *
 * <p>每个请求都渲染而不只在某个端点渲染：前端第一个请求未必是 {@code GET /api/session}，
 * 按端点判断会留下「先调了别的接口就一直没有 token」的死角。
 */
final class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            // 取值这个动作本身触发 deferred token 渲染，进而写 cookie
            csrfToken.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
