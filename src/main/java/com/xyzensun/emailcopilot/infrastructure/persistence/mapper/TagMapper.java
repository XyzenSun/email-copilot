package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Tag;
import com.xyzensun.emailcopilot.infrastructure.persistence.projection.TagWithMessageCount;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 标签。删除时须同事务清理 {@code message.tags} 的数组残留。
 */
public interface TagMapper extends BaseMapper<Tag> {

    /**
     * 列出标签并实时统计引用邮件数。
     *
     * <p>相关子查询保持 {@code tags @> ARRAY[id]} 的形状，使 PostgreSQL 能使用
     * {@code ix_message_tags} GIN 索引；不把全库数组读到 Java 再逐行计数。
     */
    @Select("""
            select t.id,
                   t.name,
                   t.display_name,
                   t.description,
                   (select count(*)
                      from message m
                     where m.tags @> array[t.id]) as message_count,
                   t.created_at,
                   t.updated_at
              from tag t
             order by t.id
            """)
    List<TagWithMessageCount> listWithMessageCount();

    @Select("""
            select t.id,
                   t.name,
                   t.display_name,
                   t.description,
                   (select count(*)
                      from message m
                     where m.tags @> array[t.id]) as message_count,
                   t.created_at,
                   t.updated_at
              from tag t
             where t.id = #{tagId}
            """)
    TagWithMessageCount getWithMessageCountById(@Param("tagId") Long tagId);

    /**
     * 从所有邮件数组移除标签 id；{@code array_remove} 会同时清掉异常重复的同一 id。
     * 调用方必须把本语句与删除 {@code tag} 行放在同一个应用层事务中。
     */
    @Update("""
            update message
               set tags = array_remove(tags, cast(#{tagId} as bigint))
             where tags @> array[cast(#{tagId} as bigint)]
            """)
    int removeTagFromMessages(@Param("tagId") Long tagId);
}
