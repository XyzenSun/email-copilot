package com.xyzensun.emailcopilot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 全局会话节点，跨账号共享（{@code DATABASE.md} §3.4）。
 *
 * <p><b>节点先于 {@link Message} 存在</b>：被提及但未收到的邮件也有节点，
 * 这样乱序到达的回复才能挂到正确的位置上。
 *
 * <p>合成标识（无 Message-ID 的邮件）只用于绑定自身 message，
 * 不创建可被他人引用的全局节点——该节点的 {@code rfcMessageId} 不会出现在
 * 任何他封邮件的 References 中。
 *
 * <p>会话摘要<b>不在此表缓存</b>：会话内容随往来动态变化，缓存会过时，
 * 每次由 AI 现算（检索会话内邮件 + 汇总）。
 */
@Data
@TableName("thread_node")
public class ThreadNode {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 全局 Message-ID，全库唯一。 */
    private String rfcMessageId;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
