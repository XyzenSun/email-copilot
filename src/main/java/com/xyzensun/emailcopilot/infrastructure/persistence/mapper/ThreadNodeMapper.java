package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.ThreadNode;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 全局会话节点，节点先于 message 存在。
 */
public interface ThreadNodeMapper extends BaseMapper<ThreadNode> {

    @Select("""
            insert into thread_node (rfc_message_id, created_at)
            values (#{rfcMessageId}, now())
            on conflict (rfc_message_id) do nothing
            returning id
            """)
    Long insertIfAbsentReturningId(@Param("rfcMessageId") String rfcMessageId);

    @Select("select id from thread_node where rfc_message_id = #{rfcMessageId}")
    Long selectIdByRfcMessageId(@Param("rfcMessageId") String rfcMessageId);
}
