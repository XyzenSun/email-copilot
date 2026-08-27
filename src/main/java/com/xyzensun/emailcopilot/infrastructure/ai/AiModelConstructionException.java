package com.xyzensun.emailcopilot.infrastructure.ai;

/**
 * AI provider client 或 ChatModel 构造失败。
 *
 * <p>异常不保留 provider 原始异常作为 cause：SDK 异常文本可能回显 Authorization、URL 中的
 * 凭据或服务商响应正文，而这个异常会沿应用层边界传播。排查所需的非敏感字段由上层另行
 * 记录，不能用完整 SDK 异常换取日志便利。
 */
public class AiModelConstructionException extends IllegalArgumentException {

    public AiModelConstructionException(String message) {
        super(message);
    }
}
