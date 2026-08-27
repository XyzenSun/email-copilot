package com.xyzensun.emailcopilot.interfaces.settings.dto;

import java.util.List;

/** 不分页的标签列表。 */
public record TagListResponse(List<TagResponse> items) {

    public TagListResponse {
        items = List.copyOf(items);
    }
}
