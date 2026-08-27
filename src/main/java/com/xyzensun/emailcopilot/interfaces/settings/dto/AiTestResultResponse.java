package com.xyzensun.emailcopilot.interfaces.settings.dto;

import com.xyzensun.emailcopilot.infrastructure.ai.AiTestResult;

/** {@code POST /api/settings/system/test} 的业务结果；三种状态都由 HTTP 200 承载。 */
public record AiTestResultResponse(String status, long latencyMs, String message) {

    public static AiTestResultResponse from(AiTestResult result) {
        return new AiTestResultResponse(result.status(), result.latencyMs(), result.message());
    }
}
