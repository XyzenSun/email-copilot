package com.xyzensun.emailcopilot.application.mail.model;

/**
 * 批量软删除结果（宽松语义，对齐 openapi BatchDeleteResult）。
 *
 * @param deleted         本次实际删除的行数（此前未删的）
 * @param alreadyDeleted  请求里有、但此前已删的数量
 * @param notFound        请求里有、但不存在的数量
 */
public record BatchDeleteResult(int deleted, int alreadyDeleted, int notFound) {
}
