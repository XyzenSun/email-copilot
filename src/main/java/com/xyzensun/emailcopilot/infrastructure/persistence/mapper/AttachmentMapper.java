package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Attachment;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 附件元数据，第一阶段不存字节、无下载。
 */
public interface AttachmentMapper extends BaseMapper<Attachment> {

    /**
     * 批量删除这些邮件的附件元数据（阶段11 删除同事务调用）。
     * 删除邮件时一并清空附件，不留孤儿附件行。
     */
    @Delete("""
            <script>
            delete from attachment
             where message_id_pk in
               <foreach collection="ids" item="id" open="(" separator="," close=")">
                 #{id}
               </foreach>
            </script>
            """)
    int deleteByMessageIds(@Param("ids") List<Long> ids);
}
