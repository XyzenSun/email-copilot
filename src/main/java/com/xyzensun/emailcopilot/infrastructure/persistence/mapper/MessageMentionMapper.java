package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MessageMention;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 邮件的上游引用（References / In-Reply-To），会话归并的输入。
 *
 * <p><b>本表是复合主键 {@code (message_id_pk, referenced_rfc_message_id)}，
 * MyBatis-Plus 不支持复合主键</b>，实体因此没有 {@code @TableId}。后果：
 * {@code insert} 与条件查询可用，而 {@code selectById} / {@code updateById} /
 * {@code deleteById} 调用会失败。
 *
 * <p>这不是缺陷——本表只按 {@code messageIdPk}（某封邮件引用了谁）或
 * {@code referencedRfcMessageId}（谁引用了某节点）成组查询，用不到 by-id 方法。
 */
public interface MessageMentionMapper extends BaseMapper<MessageMention> {

    @Update("""
            insert into message_mention
                (message_id_pk, referenced_rfc_message_id, position)
            values
                (#{mention.messageIdPk}, #{mention.referencedRfcMessageId}, #{mention.position})
            on conflict (message_id_pk, referenced_rfc_message_id) do nothing
            """)
    int insertIfAbsent(@Param("mention") MessageMention mention);
}
