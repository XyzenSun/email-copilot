package com.xyzensun.emailcopilot.domain.pipeline;

import java.text.Normalizer;

/**
 * 使用 JDK Unicode script 判断正文是否主要为中文。
 *
 * <p>这里只回答“是否需要翻译”，不猜测具体 locale。只统计字母可避免数字、标点和 emoji
 * 稀释比例；NFKC 先把兼容汉字归一化，使视觉等价文本得到相同结果。
 */
public final class LanguageDetector {

    public LanguageDetection detect(String bodyText) {
        if (bodyText == null) {
            return LanguageDetection.UNKNOWN;
        }

        String normalizedText = Normalizer.normalize(bodyText, Normalizer.Form.NFKC);
        int letterCount = 0;
        int hanCount = 0;
        int kanaCount = 0;
        int hangulCount = 0;

        for (int offset = 0; offset < normalizedText.length();) {
            int codePoint = normalizedText.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (!Character.isLetter(codePoint)) {
                continue;
            }

            letterCount++;
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            switch (script) {
                case HAN -> hanCount++;
                case HIRAGANA, KATAKANA -> kanaCount++;
                case HANGUL -> hangulCount++;
                default -> {
                    // 其它脚本只参与总字母数，用于判断 Han 是否达到绝对 50% 门槛。
                }
            }
        }

        if (letterCount == 0) {
            return LanguageDetection.UNKNOWN;
        }

        boolean hanIsAtLeastHalf = (long) hanCount * 2 >= letterCount;
        boolean kanaIsBelowHalfOfHan = (long) kanaCount * 2 < hanCount;
        boolean hangulIsBelowHalfOfHan = (long) hangulCount * 2 < hanCount;
        if (hanIsAtLeastHalf && kanaIsBelowHalfOfHan && hangulIsBelowHalfOfHan) {
            return LanguageDetection.CHINESE;
        }
        return LanguageDetection.NON_CHINESE;
    }
}
