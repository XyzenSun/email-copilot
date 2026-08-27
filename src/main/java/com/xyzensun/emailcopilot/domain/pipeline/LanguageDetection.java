package com.xyzensun.emailcopilot.domain.pipeline;

/** 确定性正文语言判断结果，只表达流水线是否需要翻译。 */
public enum LanguageDetection {
    CHINESE,
    NON_CHINESE,
    UNKNOWN
}
