package com.xyzensun.emailcopilot.domain.pipeline;

/** 自动分类与自动标签在同一个内部阶段中的启用组合。 */
public enum ClassificationMode {
    CATEGORY_AND_TAGS,
    CATEGORY_ONLY,
    TAGS_ONLY,
    SKIP
}
