package com.xyzensun.emailcopilot.application.settings;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Tag;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.TagMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.projection.TagWithMessageCount;
import com.xyzensun.emailcopilot.interfaces.error.ApiError;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import com.xyzensun.emailcopilot.interfaces.error.ValidationErrorItem;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 标签设置用例。
 *
 * <p>{@code name} 是不可变引用锚点，{@code description} 是流水线 AI 的判定依据而非普通备注。
 * {@code messageCount} 每次从 PostgreSQL 数组实时统计，不保存派生值。
 */
@Service
public class TagApplicationService {

    private final TagMapper tagMapper;

    public TagApplicationService(TagMapper tagMapper) {
        this.tagMapper = tagMapper;
    }

    @Transactional(readOnly = true)
    public List<TagView> listTags() {
        return tagMapper.listWithMessageCount().stream()
                .map(TagApplicationService::toView)
                .toList();
    }

    @Transactional
    public TagView createTag(CreateCommand command) {
        validateCreateCommand(command);
        if (!isAsciiAlphanumeric(command.name())) {
            throw new ApiException(ApiError.INVALID_TAG_NAME);
        }

        Tag tag = new Tag();
        tag.setName(command.name());
        tag.setDisplayName(command.displayName());
        tag.setDescription(command.description());

        try {
            tagMapper.insert(tag);
        } catch (DuplicateKeyException ex) {
            // 不做“先查再插”；并发下只有数据库唯一约束能给出确定答案。
            throw new ApiException(ApiError.TAG_NAME_TAKEN);
        }
        return getPersistedView(tag.getId());
    }

    @Transactional
    public TagView updateTag(long tagId, UpdateCommand command) {
        if (command.immutableNamePresent()) {
            // 即使提交值与当前 name 相同或显式为 null，也属于试图修改不可变锚点。
            throw new ApiException(ApiError.TAG_NAME_IMMUTABLE);
        }
        validateUpdateCommand(command);

        LambdaUpdateWrapper<Tag> update = new LambdaUpdateWrapper<Tag>()
                .eq(Tag::getId, tagId);
        if (command.displayNamePresent()) {
            update.set(Tag::getDisplayName, command.displayName());
        }
        if (command.descriptionPresent()) {
            update.set(Tag::getDescription, command.description());
        }
        update.setSql("updated_at = now()");

        if (tagMapper.update(null, update) == 0) {
            throw new ApiException(ApiError.TAG_NOT_FOUND);
        }
        // 重新读取数据库事实源，避免用 PATCH 前的旧实体覆盖另一个并发字段的更新。
        return getPersistedView(tagId);
    }

    /**
     * 删除标签，并在同一事务中移除所有邮件数组中的残留 id。
     *
     * <p>先清数组再删标签；若标签不存在，随后抛出的运行时异常会回滚前一条 UPDATE，
     * 因而一次 404 请求不会暗中修改孤立数组。
     */
    @Transactional
    public void deleteTag(long tagId) {
        tagMapper.removeTagFromMessages(tagId);
        if (tagMapper.deleteById(tagId) == 0) {
            throw new ApiException(ApiError.TAG_NOT_FOUND);
        }
    }

    private TagView getPersistedView(long tagId) {
        TagWithMessageCount projection = tagMapper.getWithMessageCountById(tagId);
        if (projection == null) {
            // 本事务刚插入/更新的行消失表示数据库状态被非预期地外部修改，不伪装成正常 404。
            throw new IllegalStateException("标签写入后无法读取");
        }
        return toView(projection);
    }

    private static void validateCreateCommand(CreateCommand command) {
        List<ValidationErrorItem> validationErrors = new ArrayList<>();
        addRequiredStringError(validationErrors, "name", command.name());
        addRequiredStringError(validationErrors, "displayName", command.displayName());
        addRequiredStringError(validationErrors, "description", command.description());
        if (!validationErrors.isEmpty()) {
            throw ApiException.validationFailed(validationErrors);
        }
    }

    private static void validateUpdateCommand(UpdateCommand command) {
        List<ValidationErrorItem> validationErrors = new ArrayList<>();
        if (!command.hasMutableField()) {
            validationErrors.add(new ValidationErrorItem("$", "至少需要提供一个可修改字段"));
        }
        if (command.displayNamePresent()) {
            addRequiredStringError(validationErrors, "displayName", command.displayName());
        }
        if (command.descriptionPresent()) {
            addRequiredStringError(validationErrors, "description", command.description());
        }
        if (!validationErrors.isEmpty()) {
            throw ApiException.validationFailed(validationErrors);
        }
    }

    private static void addRequiredStringError(
            List<ValidationErrorItem> validationErrors, String field, String value) {
        if (value == null || value.isBlank()) {
            validationErrors.add(new ValidationErrorItem(field, "不能为空"));
        }
    }

    private static boolean isAsciiAlphanumeric(String name) {
        for (int index = 0; index < name.length(); index++) {
            char currentCharacter = name.charAt(index);
            boolean isAsciiLetter = (currentCharacter >= 'A' && currentCharacter <= 'Z')
                    || (currentCharacter >= 'a' && currentCharacter <= 'z');
            boolean isDigit = currentCharacter >= '0' && currentCharacter <= '9';
            if (!isAsciiLetter && !isDigit) {
                return false;
            }
        }
        return true;
    }

    private static TagView toView(TagWithMessageCount projection) {
        return new TagView(
                projection.getId(),
                projection.getName(),
                projection.getDisplayName(),
                projection.getDescription(),
                projection.getMessageCount(),
                projection.getCreatedAt(),
                projection.getUpdatedAt());
    }

    public record CreateCommand(String name, String displayName, String description) {
    }

    public record UpdateCommand(
            boolean immutableNamePresent,
            boolean displayNamePresent,
            String displayName,
            boolean descriptionPresent,
            String description) {

        public boolean hasMutableField() {
            return displayNamePresent || descriptionPresent;
        }
    }

    public record TagView(
            Long id,
            String name,
            String displayName,
            String description,
            Long messageCount,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }
}
