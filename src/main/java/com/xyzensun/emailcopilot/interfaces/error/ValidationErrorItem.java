package com.xyzensun.emailcopilot.interfaces.error;

/**
 * 字段级校验错误的一项，只出现在 {@link ApiError#VALIDATION_FAILED} 的
 * {@code errors} 扩展成员里（{@code API.md} §3.2）。
 *
 * <p>{@code field} 用点号表达嵌套路径，与 Bean Validation 的属性路径一致
 * （如 {@code recipients.to}），前端据此把错误定位到具体输入框。
 */
public record ValidationErrorItem(String field, String message) {
}
