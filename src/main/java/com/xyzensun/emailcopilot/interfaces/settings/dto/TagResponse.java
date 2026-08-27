package com.xyzensun.emailcopilot.interfaces.settings.dto;

import com.xyzensun.emailcopilot.application.settings.TagApplicationService.TagView;

import java.time.OffsetDateTime;

/** 标签响应；{@code messageCount} 是 PostgreSQL 实时统计值。 */
public record TagResponse(
        Long id,
        String name,
        String displayName,
        String description,
        Long messageCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static TagResponse from(TagView view) {
        return new TagResponse(
                view.id(),
                view.name(),
                view.displayName(),
                view.description(),
                view.messageCount(),
                view.createdAt(),
                view.updatedAt());
    }
}
