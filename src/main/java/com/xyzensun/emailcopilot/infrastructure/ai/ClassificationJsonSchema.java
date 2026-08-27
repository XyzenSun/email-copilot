package com.xyzensun.emailcopilot.infrastructure.ai;

import com.xyzensun.emailcopilot.domain.enums.MessageCategory;
import com.xyzensun.emailcopilot.domain.pipeline.ClassificationMode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static java.util.Objects.requireNonNull;

/** 根据本次分类/标签开关模式严格解析完整 assistant content。 */
public final class ClassificationJsonSchema {

    private static final Set<String> CATEGORY_FIELD = Set.of("category");
    private static final Set<String> TAG_FIELDS = Set.of("tag_names");
    private static final Set<String> CATEGORY_AND_TAG_FIELDS = Set.of("category", "tag_names");
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build();

    public ClassificationResult parse(
            String assistantContent,
            ClassificationMode mode,
            Map<String, Long> allowedTagIdsByName) {
        requireNonNull(mode, "分类模式不能为空");
        requireNonNull(allowedTagIdsByName, "可用标签映射不能为空");
        try {
            if (assistantContent == null || mode == ClassificationMode.SKIP) {
                throw new InvalidStructuredOutputException();
            }
            JsonNode root = JSON.readTree(assistantContent);
            if (root == null || !root.isObject()
                    || !Set.copyOf(root.propertyNames()).equals(expectedFields(mode))) {
                throw new InvalidStructuredOutputException();
            }

            Optional<MessageCategory> category = mode == ClassificationMode.CATEGORY_ONLY
                    || mode == ClassificationMode.CATEGORY_AND_TAGS
                    ? Optional.of(parseCategory(root.get("category")))
                    : Optional.empty();
            List<Long> tagIds = mode == ClassificationMode.TAGS_ONLY
                    || mode == ClassificationMode.CATEGORY_AND_TAGS
                    ? parseTagNames(root.get("tag_names"), allowedTagIdsByName)
                    : List.of();
            return new ClassificationResult(category, tagIds);
        } catch (InvalidStructuredOutputException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidStructuredOutputException();
        }
    }

    private static Set<String> expectedFields(ClassificationMode mode) {
        return switch (mode) {
            case CATEGORY_AND_TAGS -> CATEGORY_AND_TAG_FIELDS;
            case CATEGORY_ONLY -> CATEGORY_FIELD;
            case TAGS_ONLY -> TAG_FIELDS;
            case SKIP -> throw new InvalidStructuredOutputException();
        };
    }

    private static MessageCategory parseCategory(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw new InvalidStructuredOutputException();
        }
        return switch (node.asString()) {
            case "primary" -> MessageCategory.PRIMARY;
            case "transaction" -> MessageCategory.TRANSACTION;
            case "promotion" -> MessageCategory.PROMOTION;
            case "social" -> MessageCategory.SOCIAL;
            case "update" -> MessageCategory.UPDATE;
            default -> throw new InvalidStructuredOutputException();
        };
    }

    private static List<Long> parseTagNames(
            JsonNode node,
            Map<String, Long> allowedTagIdsByName) {
        if (node == null || !node.isArray()) {
            throw new InvalidStructuredOutputException();
        }
        TreeSet<Long> normalizedIds = new TreeSet<>();
        for (JsonNode item : node.values()) {
            if (!item.isTextual()) {
                throw new InvalidStructuredOutputException();
            }
            Long id = allowedTagIdsByName.get(item.asString());
            if (id == null || id <= 0) {
                throw new InvalidStructuredOutputException();
            }
            normalizedIds.add(id);
        }
        return new ArrayList<>(normalizedIds);
    }
}
