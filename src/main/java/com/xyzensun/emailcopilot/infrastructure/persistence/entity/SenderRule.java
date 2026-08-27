package com.xyzensun.emailcopilot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xyzensun.emailcopilot.domain.enums.SenderRuleType;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 用户预先声明的发件人规则，<b>按已认证域名匹配</b>（{@code DATABASE.md} §4.6）。
 *
 * <p>规则匹配的是 {@code message.fromAuthenticatedDomain}，<b>不是 From 字面值</b>——
 * DKIM 认证失败的邮件任何信任规则都不生效。
 *
 * <p>{@link #domainPattern} 按域名分段逐段比较，<b>不用正则</b>：
 * {@code *} 恰好一层（{@code *.a.com} 匹配 {@code x.a.com} 但不匹配 {@code x.y.a.com}），
 * {@code +} 任意层含裸域名（{@code +.a.com} 匹配 {@code a.com}、{@code x.a.com}、
 * {@code x.y.a.com}）。
 *
 * <p><b>规则变更不回溯已判定的邮件。</b>判定在收信时一次完成并写入
 * {@code message.category}；今天新增一条屏蔽规则，昨天收到的邮件不会变成 spam。
 * 第一阶段不提供"按新规则重新判定历史邮件"（那要重置全部 processing_progress、
 * 重跑流水线、重新消耗模型调用与取证配额）。<b>界面必须写明这一点</b>，
 * 否则用户会以为规则没生效而反复检查。
 */
@Data
@TableName("sender_rule")
public class SenderRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private SenderRuleType ruleType;

    /** {@code *.a.com} 或 {@code +.a.com}，应用按前缀解析。 */
    private String domainPattern;

    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
