package com.xyzensun.emailcopilot.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletResponse;

import java.util.function.Supplier;

/**
 * SPA 用的 CSRF token 处理器（Spring Security 官方「Configuring CSRF for SPA」写法）。
 *
 * <p><b>不写这个类的后果：CSRF 明明按文档配好了，所有写操作却全是 403，包括登录本身——
 * 于是登录页什么都点不动，而配置看起来完全正确。</b>
 *
 * <p>成因是 Spring Security 6+ 默认的 {@link XorCsrfTokenRequestAttributeHandler}
 * 为防 BREACH 攻击做了随机掩码：
 * <ul>
 *   <li>写进 {@code XSRF-TOKEN} cookie 的是 <b>raw token</b>；</li>
 *   <li>而它期望请求头里送来的是 <b>XOR 编码值</b>。</li>
 * </ul>
 * SPA 从 cookie 读出 raw token 直接放进 {@code X-XSRF-TOKEN}，去掩码后自然对不上。
 *
 * <p>因此按来源分流：
 * <ul>
 *   <li><b>写入</b>仍走 XOR，保留 BREACH 保护；</li>
 *   <li><b>解析</b>时若带了请求头，说明是 SPA 直接送 cookie 里的 raw token，用不做掩码的
 *       {@link CsrfTokenRequestAttributeHandler}；否则（表单提交的隐藏字段）仍走 XOR。</li>
 * </ul>
 *
 * <p>{@link XorCsrfTokenRequestAttributeHandler} 是 {@code final} 类，只能组合不能继承。
 */
final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler maskedHandler = new XorCsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler plainHandler = new CsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       Supplier<CsrfToken> deferredCsrfToken) {
        maskedHandler.handle(request, response, deferredCsrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        return StringUtils.hasText(request.getHeader(csrfToken.getHeaderName()))
                ? plainHandler.resolveCsrfTokenValue(request, csrfToken)
                : maskedHandler.resolveCsrfTokenValue(request, csrfToken);
    }
}
