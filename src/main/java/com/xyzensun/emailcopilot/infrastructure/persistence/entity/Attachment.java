package com.xyzensun.emailcopilot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 邮件附件（{@code DATABASE.md} §3.6）。
 *
 * <p><b>第一阶段只存元数据，不解析内容、不存字节、无下载接口</b>——
 * 附件是攻击载荷最集中的位置。前端展示文件名与大小，供用户判断是否回原邮箱查看。
 *
 * <p>DataPurge 时直接删除该邮件的全部附件行（无最小删除记忆需求，
 * 与 {@link Message} 只清正文保留行不同）。
 */
@Data
@TableName("attachment")
public class Attachment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 逻辑引用 message。 */
    private Long messageIdPk;

    private String filename;

    private String contentType;

    private Integer sizeBytes;
}
