package com.xyzensun.emailcopilot.interfaces.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

/**
 * 把业务异常统一转成 RFC 9457 Problem Details（{@code API.md} §3.3）。
 *
 * <p><b>不依赖 {@code spring.mvc.problemdetails.enabled} 的自动转换</b>：
 * 需要注入自定义扩展成员（{@code code}、{@code errors}），必须自己控制构造。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ProblemDetailFactory problemDetailFactory;

    public GlobalExceptionHandler(ProblemDetailFactory problemDetailFactory) {
        this.problemDetailFactory = problemDetailFactory;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        ProblemDetail problem = problemDetailFactory.create(
                ex.error(), ex.detail(), request.getRequestURI());
        if (!ex.validationErrors().isEmpty()) {
            problem.setProperty("errors", ex.validationErrors());
        }
        return problemResponse(problem);
    }

    /**
     * Bean Validation 失败 → {@link ApiError#VALIDATION_FAILED} + 逐字段的 {@code errors}。
     *
     * <p>父类在 Spring 6+ 已经会返回 {@code ProblemDetail}，但那份没有 {@code code} 扩展成员，
     * 前端无从分支，所以整个替换掉。
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        List<ValidationErrorItem> errors = ex.getBindingResult().getAllErrors().stream()
                .map(error -> new ValidationErrorItem(
                        error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName(),
                        error.getDefaultMessage()))
                .toList();

        return validationProblem(errors, request);
    }

    /**
     * 数字 path variable、query parameter 等 Spring 参数绑定转换失败也必须走同一套
     * Problem Details；不能把原始 rejected value 放进 detail，因为它可能是凭据或其它
     * 用户不应被回显的敏感输入。
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return validationProblemWithoutRequestInput(
                List.of(new ValidationErrorItem("$", "请求参数类型不正确")));
    }

    /**
     * JSON 语法错误、缺请求体、字段类型无法转换都属于 {@link ApiError#VALIDATION_FAILED}。
     *
     * <p>不能交给父类默认处理：默认 Problem Details 没有 {@code code}，而前端只据
     * {@code code} 分支。也不能把 {@link HttpMessageNotReadableException#getMessage()}
     * 放进 {@code detail}——Jackson 的异常消息可能带请求体片段，登录接口的片段里就是明文口令。
     * 用 {@code "$"} 表示整个请求体，而不猜一个可能不准确的字段名。
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return validationProblem(
                List.of(new ValidationErrorItem("$", "请求体不是合法的 JSON 或字段类型不正确")),
                request);
    }

    private ResponseEntity<Object> validationProblem(
            List<ValidationErrorItem> errors, WebRequest request) {
        return validationProblem(
                errors, request.getDescription(false).replaceFirst("^uri=", ""));
    }

    /**
     * path/query 转换失败时请求 URI 本身包含 rejected value，省略可选的 instance 字段，
     * 避免把可能敏感的原始输入复制进响应。
     */
    private ResponseEntity<Object> validationProblemWithoutRequestInput(
            List<ValidationErrorItem> errors) {
        // Spring MVC 会为 null instance 自动回填完整请求 URI；显式使用根路径可阻止
        // path variable 的 rejected value 被框架重新复制进响应。
        return validationProblem(errors, "/");
    }

    private ResponseEntity<Object> validationProblem(
            List<ValidationErrorItem> errors, String instance) {
        ProblemDetail problem = problemDetailFactory.create(
                ApiError.VALIDATION_FAILED, null, instance);
        problem.setProperty("errors", errors);

        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(problem, responseHeaders, ApiError.VALIDATION_FAILED.status());
    }

    /**
     * 兜底：未预期的异常一律 500，<b>堆栈只进服务端日志</b>，{@code detail} 只给可公开的摘要。
     *
     * <p>Spring Security 的两类异常必须原样抛出去。它们由 filter 层的
     * {@code ExceptionTranslationFilter} 翻译成 401/403，而本处理器位于 DispatcherServlet 层、
     * 执行得更早——在这里吃掉它们，「未登录」和「CSRF 不匹配」就会变成 500，
     * 前端拿到的是「服务内部错误」而不是「请重新登录」，于是既不跳登录页也没人知道为什么。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request)
            throws Exception {
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex;
        }
        log.error("未处理的异常，请求 {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problemResponse(problemDetailFactory.create(
                ApiError.INTERNAL_ERROR, null, request.getRequestURI()));
    }

    private ResponseEntity<ProblemDetail> problemResponse(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
