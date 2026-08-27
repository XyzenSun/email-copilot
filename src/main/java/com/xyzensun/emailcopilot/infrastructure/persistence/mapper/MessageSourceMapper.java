package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MessageSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 邮件来源通道，FirstIngestWins 的载体。
 */
public interface MessageSourceMapper extends BaseMapper<MessageSource> {

    /**
     * 同一通道重复送达保持幂等。无目标的 ON CONFLICT 也覆盖 V2 canonical 部分唯一索引；
     * 首次入库要求返回 1，若返回 0 由应用视为不变式破坏而整体回滚。
     */
    @Update("""
            insert into message_source
                (message_id_pk, channel_type, is_canonical, received_at)
            values
                (#{source.messageIdPk}, #{source.channelType},
                 #{source.isCanonical}, #{source.receivedAt})
            on conflict do nothing
            """)
    int insertIfAbsent(@Param("source") MessageSource source);
}
