package com.xyzensun.emailcopilot.infrastructure.ai;

/** XML-like prompt delimiter 的最小转义，防止不可信文本提前闭合数据块。 */
public final class PromptTextEscaper {

    private PromptTextEscaper() {
    }

    public static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public static String escapeNullable(String value) {
        return value == null ? "null" : escape(value);
    }
}
