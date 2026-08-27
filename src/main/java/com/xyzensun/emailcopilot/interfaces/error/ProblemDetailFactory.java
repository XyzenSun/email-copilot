package com.xyzensun.emailcopilot.interfaces.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * 由 {@link ApiError} 构造 RFC 9457 Problem Details。
 *
 * <p>存在的理由是<b>本项目有四个出口要吐这个结构，而其中三个不经过 Controller</b>：
 * 未登录被拦（{@code AuthenticationEntryPoint}）、CSRF 不匹配（{@code AccessDeniedHandler}）、
 * 改口令后被作废的 session 再来请求（{@code SessionInformationExpiredStrategy}）。
 * 这三条路径 {@code @RestControllerAdvice} 管不到，各自手拼一份 JSON 的话，
 * 字段迟早悄悄不一致——前端只据 {@code code} 分支，某个出口漏了 {@code code}
 * 就会退化成「只有状态码可用」，而这在浏览器里表现为「登录页莫名其妙不跳转」。
 *
 * <p><b>必须用 Spring 容器里的 {@link JsonMapper}</b>，不能自建：
 * Spring Boot 给它注册了 {@code ProblemDetail} 的 Jackson mixin，负责把
 * {@code getProperties()} 里的扩展成员展平成顶层字段。自建的 mapper 没有这个 mixin，
 * 输出会变成 {@code {"properties":{"code":"..."}}}——多一层壳，前端读 {@code code} 读不到。
 */
@Component
public class ProblemDetailFactory {

    private final JsonMapper jsonMapper;

    public ProblemDetailFactory(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * @param detail 运行时拼出的上下文，可为 null。
     *               <b>绝不能包含凭据、密钥或邮件正文</b>（{@code API.md} §3.3）。
     */
    public ProblemDetail create(ApiError error, String detail, String instance) {
        ProblemDetail problem = ProblemDetail.forStatus(error.status());
        problem.setType(error.type());
        problem.setTitle(error.title());
        problem.setDetail(detail);
        problem.setProperty("code", error.code());
        if (instance != null) {
            problem.setInstance(URI.create(instance));
        }
        return problem;
    }

    /**
     * 直接把 Problem Details 写进响应，供 Spring Security 的三个非 Controller 出口使用。
     *
     * <p>响应已提交时静默返回：此时状态码与部分响应体已经发出，再写只会在日志里堆一个
     * {@code IllegalStateException}，而客户端那边已经无法挽回。
     */
    public void write(HttpServletRequest request, HttpServletResponse response,
                      ApiError error, String detail) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(error.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        jsonMapper.writeValue(response.getWriter(), create(error, detail, request.getRequestURI()));
    }
}
