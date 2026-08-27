package com.xyzensun.emailcopilot.interfaces.settings.dto;

/**
 * 标签部分更新请求。
 *
 * <p>{@code name} 不作为可读字段暴露，但保留 write-only setter 来记录它是否出现在 JSON 中。
 * setter 接受 {@link Object}，因此无论值是字符串、null 还是其它 JSON 类型，都能稳定映射为
 * {@code TAG_NAME_IMMUTABLE}，不会先被类型转换错误吞成普通 400。
 */
public final class TagUpdateRequest {

    private boolean immutableNamePresent;
    private boolean displayNamePresent;
    private String displayName;
    private boolean descriptionPresent;
    private String description;

    public void setName(Object ignoredName) {
        this.immutableNamePresent = true;
    }

    public void setDisplayName(String displayName) {
        this.displayNamePresent = true;
        this.displayName = displayName;
    }

    public void setDescription(String description) {
        this.descriptionPresent = true;
        this.description = description;
    }

    public boolean hasImmutableName() {
        return immutableNamePresent;
    }

    public boolean hasDisplayName() {
        return displayNamePresent;
    }

    public String displayName() {
        return displayName;
    }

    public boolean hasDescription() {
        return descriptionPresent;
    }

    public String description() {
        return description;
    }
}
