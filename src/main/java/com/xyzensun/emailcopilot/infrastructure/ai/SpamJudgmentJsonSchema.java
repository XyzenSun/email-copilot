package com.xyzensun.emailcopilot.infrastructure.ai;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 严格解析垃圾评分的唯一模型输出 {@code {"spam_score": number}}。
 *
 * <p>解析完整 assistant content，既不截取 JSON 片段，也不修补错误；失败异常不携带原文，
 * 防止邮件内容或 provider 响应进入日志和测试报告。
 */
public final class SpamJudgmentJsonSchema {

    private static final Set<String> EXPECTED_FIELDS = Set.of("spam_score");
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    public SpamJudgmentResult parse(String assistantContent) {
        try {
            if (assistantContent == null) {
                throw new InvalidStructuredOutputException();
            }
            JsonNode root = JSON.readTree(assistantContent);
            if (root == null || !root.isObject() || !root.propertyNames().equals(EXPECTED_FIELDS)) {
                throw new InvalidStructuredOutputException();
            }

            JsonNode scoreNode = root.get("spam_score");
            if (scoreNode == null || !scoreNode.isNumber()) {
                throw new InvalidStructuredOutputException();
            }
            BigDecimal spamScore = scoreNode.decimalValue();
            if (spamScore.compareTo(BigDecimal.ZERO) < 0
                    || spamScore.compareTo(BigDecimal.ONE) > 0) {
                throw new InvalidStructuredOutputException();
            }
            return new SpamJudgmentResult(spamScore);
        } catch (InvalidStructuredOutputException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidStructuredOutputException();
        }
    }
}
