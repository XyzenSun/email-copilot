package com.xyzensun.emailcopilot.domain.mail;

/** RFC 邮件基础主题的确定性剥离；只供 JWZ 根集兜底，不作为引用归并的否决条件。 */
public final class BaseSubject {

    private static final int MAX_PREFIX_PASSES = 32;

    private BaseSubject() {
    }

    public static String extract(String subject) {
        if (subject == null) {
            return null;
        }
        String value = subject.strip();
        for (int pass = 0; pass < MAX_PREFIX_PASSES; pass++) {
            String stripped = stripOnePrefix(value);
            if (stripped.equals(value)) {
                break;
            }
            value = stripped.strip();
        }
        return value.replaceAll("\\s+", " ").strip();
    }

    private static String stripOnePrefix(String value) {
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close > 0 && close < 128) {
                return value.substring(close + 1);
            }
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        for (String prefix : new String[]{"re:", "fw:", "fwd:"}) {
            if (lower.startsWith(prefix)) {
                return value.substring(prefix.length());
            }
        }
        return value;
    }
}
