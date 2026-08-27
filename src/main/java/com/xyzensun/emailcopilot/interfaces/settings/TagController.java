package com.xyzensun.emailcopilot.interfaces.settings;

import com.xyzensun.emailcopilot.application.settings.TagApplicationService;
import com.xyzensun.emailcopilot.application.settings.TagApplicationService.CreateCommand;
import com.xyzensun.emailcopilot.application.settings.TagApplicationService.UpdateCommand;
import com.xyzensun.emailcopilot.interfaces.settings.dto.TagCreateRequest;
import com.xyzensun.emailcopilot.interfaces.settings.dto.TagListResponse;
import com.xyzensun.emailcopilot.interfaces.settings.dto.TagResponse;
import com.xyzensun.emailcopilot.interfaces.settings.dto.TagUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 标签设置接口（{@code API.md} §12.5）。 */
@RestController
@RequestMapping("/api/tags")
public class TagController {

    private final TagApplicationService tagApplicationService;

    public TagController(TagApplicationService tagApplicationService) {
        this.tagApplicationService = tagApplicationService;
    }

    @GetMapping
    public TagListResponse listTags() {
        return new TagListResponse(tagApplicationService.listTags().stream()
                .map(TagResponse::from)
                .toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TagResponse createTag(@Valid @RequestBody TagCreateRequest request) {
        return TagResponse.from(tagApplicationService.createTag(
                new CreateCommand(request.name(), request.displayName(), request.description())));
    }

    @PatchMapping("/{id}")
    public TagResponse updateTag(@PathVariable("id") long tagId,
                                 @RequestBody TagUpdateRequest request) {
        return TagResponse.from(tagApplicationService.updateTag(
                tagId,
                new UpdateCommand(
                        request.hasImmutableName(),
                        request.hasDisplayName(),
                        request.displayName(),
                        request.hasDescription(),
                        request.description())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTag(@PathVariable("id") long tagId) {
        tagApplicationService.deleteTag(tagId);
    }
}
