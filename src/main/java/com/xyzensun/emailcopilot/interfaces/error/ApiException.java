package com.xyzensun.emailcopilot.interfaces.error;

import java.util.List;

/**
 * 业务异常，携带 {@link ApiError} 与运行时拼出的 {@code detail}。
 *
 * <p>由 {@link GlobalExceptionHandler} 统一转成 RFC 9457 Problem Details。
 *
 * <p><b>{@code detail} 里绝不能出现凭据、密钥与邮件正文</b>（{@code API.md} §3.3）。
 * 最容易犯的形态是把外部服务返回的原文直接拼进去——SMTP / IMAP 的错误信息里可能带用户名，
 * 而错误响应是会被前端展示、被日志记录的。
 *
 * <p>不继承受检异常：这类异常在每一层都只是往上抛、由统一处理器接住，
 * 沿途声明 {@code throws} 只会污染签名而没有任何调用方会分别处理。
 */
public class ApiException extends RuntimeException {

    private final ApiError error;
    private final List<ValidationErrorItem> validationErrors;

    public ApiException(ApiError error) {
        this(error, null, List.of());
    }

    public ApiException(ApiError error, String detail) {
        this(error, detail, List.of());
    }

    private ApiException(ApiError error, String detail, List<ValidationErrorItem> validationErrors) {
        super(detail);
        this.error = error;
        this.validationErrors = List.copyOf(validationErrors);
    }

    /**
     * 构造带字段级错误的 {@link ApiError#VALIDATION_FAILED}。
     *
     * <p>只有这一个错误码带 {@code errors} 扩展成员（{@code API.md} §3.2），
     * 因此用静态工厂而不是公开构造器——避免给其它错误码也塞上 {@code errors}。
     */
    public static ApiException validationFailed(List<ValidationErrorItem> errors) {
        return new ApiException(ApiError.VALIDATION_FAILED, null, errors);
    }

    public ApiError error() {
        return error;
    }

    /** 运行时拼接的具体上下文，可空。 */
    public String detail() {
        return getMessage();
    }

    public List<ValidationErrorItem> validationErrors() {
        return validationErrors;
    }
}
