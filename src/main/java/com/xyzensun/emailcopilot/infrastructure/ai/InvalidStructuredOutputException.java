package com.xyzensun.emailcopilot.infrastructure.ai;

/** 模型返回内容不符合固定结构化输出契约；异常文本不得包含 provider 原文。 */
public final class InvalidStructuredOutputException extends RuntimeException {

    public InvalidStructuredOutputException() {
        super("AI 返回内容不符合固定结构化输出契约");
    }
}
