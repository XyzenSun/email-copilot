package com.xyzensun.emailcopilot.infrastructure.persistence.projection;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 标签及其当前引用邮件数的只读查询投影。
 *
 * <p>{@code messageCount} 是查询时从 {@code message.tags bigint[]} 派生的值，不落库；
 * 独立投影避免让持久化实体携带一个数据库中并不存在、写回时容易误用的字段。
 */
@Data
public class TagWithMessageCount {

    private Long id;
    private String name;
    private String displayName;
    private String description;
    private Long messageCount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
