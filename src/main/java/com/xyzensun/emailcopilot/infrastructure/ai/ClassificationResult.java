package com.xyzensun.emailcopilot.infrastructure.ai;

import com.xyzensun.emailcopilot.domain.enums.MessageCategory;

import java.util.List;
import java.util.Optional;

/** 已按当前分类/标签开关模式严格验证的模型结果。 */
public record ClassificationResult(
        Optional<MessageCategory> category,
        List<Long> tagIds) {

    public ClassificationResult {
        category = category == null ? Optional.empty() : category;
        tagIds = List.copyOf(tagIds);
    }
}
