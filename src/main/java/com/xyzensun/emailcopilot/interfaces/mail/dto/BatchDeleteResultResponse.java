package com.xyzensun.emailcopilot.interfaces.mail.dto;

import com.xyzensun.emailcopilot.application.mail.model.BatchDeleteResult;

/** 批量删除结果响应（openapi BatchDeleteResult），宽松计数三类。 */
public record BatchDeleteResultResponse(int deleted, int alreadyDeleted, int notFound) {

    public static BatchDeleteResultResponse from(BatchDeleteResult result) {
        return new BatchDeleteResultResponse(result.deleted(), result.alreadyDeleted(), result.notFound());
    }
}
