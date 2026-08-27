package com.xyzensun.emailcopilot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 标签（{@code DATABASE.md} §4.1）。
 *
 * <p>{@link #name} 是字符串标识、作为引用锚点、<b>创建后不可改</b>（限 {@code [A-Za-z0-9]}，
 * 由应用层校验，不设 DB check）；{@link #id} 是自增主键，用于 {@code message.tags} 数组引用。
 *
 * <p><b>{@link #description} 不是给人看的备注，而是打标签那一步实际拼进提示词的内容</b>
 * （{@code DATABASE.md} §4.2.1）。流水线在 classification 阶段读出全部标签，
 * 拼成清单交给模型，模型只能从清单里选。因此<b>改描述即改标注行为，
 * 不需要改代码也不需要重启</b>——这正是把它设计成 not null 文本列的原因。
 *
 * <p>界面上必须把这件事说清楚：那个输入框写的是给 AI 的判定规则，不是给自己看的备注。
 * 用户以为在写备注、随手填一句"这个标签是我自己用的"，AI 就会照着这句话去判断。
 * 两条经验值得写进输入框提示：<b>说清楚什么算</b>，<b>顺带说清楚什么不算</b>
 * （只写"发票相关"会让模型把所有提到钱的邮件都打上）。
 *
 * <p>删除标签时须同步清理 {@code message.tags} 里的数组残留，与删本行同事务——
 * 残留 id 会让邮件详情返回一个查不到名字的标签，前端只能显示空白或报错。
 */
@Data
@TableName("tag")
public class Tag {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 标签标识，用户填，创建后不可改。 */
    private String name;

    /** 展示名，可改。 */
    private String displayName;

    /** 写给 AI 的判定依据，会被<b>原样注入提示词</b>。 */
    private String description;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
