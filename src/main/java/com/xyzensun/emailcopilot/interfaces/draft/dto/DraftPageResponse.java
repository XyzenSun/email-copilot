package com.xyzensun.emailcopilot.interfaces.draft.dto;

import com.xyzensun.emailcopilot.application.draft.model.DraftView;

import java.util.List;

/** 草稿分页列表响应（openapi {@code DraftPage}）。正文一次全给，按 updatedAt 倒序。 */
public record DraftPageResponse(
        int page,
        int size,
        long total,
        List<DraftResponse> items) {

    public static DraftPageResponse of(int page, int size, long total, List<DraftView> views) {
        List<DraftResponse> items = views.stream().map(DraftResponse::from).toList();
        return new DraftPageResponse(page, size, total, items);
    }
}
