package com.xyzensun.emailcopilot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xyzensun.emailcopilot.domain.Recipients;
import com.xyzensun.emailcopilot.domain.enums.MessageCategory;
import com.xyzensun.emailcopilot.domain.enums.MessageDirection;
import com.xyzensun.emailcopilot.infrastructure.persistence.handler.LongListArrayTypeHandler;
import com.xyzensun.emailcopilot.infrastructure.persistence.handler.RecipientsJsonbTypeHandler;
import lombok.Data;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 一封具体邮件。<b>收到的与自己发出的都在本表</b>，由 {@code direction} 区分
 * （{@code DATABASE.md} §3.2、§3.2.1）。
 *
 * <p>分类、垃圾置信度、翻译、摘要作为本表的 typed columns 保存，不另建判定表，
 * 也不保留判定变更历史——单用户自用不审计判定变更。
 *
 * <p>全部布尔与数值字段用包装类型：数据库里多为 nullable，包装类型同时避免 Java
 * primitive 默认值掩盖数据库 null。垃圾可见性只由 {@code category=spam} 表达，不再保存
 * 与分类重复的布尔字段。
 */
@Data
@TableName(value = "message", autoResultMap = true)
public class Message {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 逻辑引用 mail_account；outbound 时为发信账号。 */
    private Long mailAccountId;

    private MessageDirection direction;

    /**
     * 发信方给的 RFC Message-ID；缺失时填合成标识。
     * outbound 时是发信时由代码自己生成并写入邮件头的值，因此永不为空、也不需要 fingerprint。
     */
    private String messageId;

    /** 无标识邮件的启发式指纹；有 messageId 时为 null。 */
    private String fingerprint;

    /** 逻辑引用 thread_node。 */
    private Long threadNodeId;

    /** 发件人显示名，<b>不可信</b>，仅列表展示。 */
    private String fromDisplay;

    private String fromAddress;

    /** 从 From 解析的字面域名。 */
    private String fromAddressDomain;

    /**
     * DKIM 通过且签名域与发件人域对齐时才填，否则 null；outbound 恒 null。
     *
     * <p><b>发件人规则匹配的是这一列，不是 {@link #fromAddress} 字面值</b>——
     * 否则伪造 From 就能绕过屏蔽规则，或冒充可信域名骗过垃圾判定。
     */
    private String fromAuthenticatedDomain;

    /**
     * 收件人/抄送/密送。inbound 也填：解析 MIME 时本就拿到了 To/Cc，
     * 顺带支持界面区分"这封直接发给我"与"我只是被 Cc"。
     */
    @TableField(typeHandler = RecipientsJsonbTypeHandler.class)
    private Recipients recipients;

    private String subject;

    /** 剥离 Re:/Fwd: 后的核心主题，会话归并用。 */
    private String baseSubject;

    /** 时间判定唯一依据；outbound 时为 SMTP 受理时刻。 */
    private OffsetDateTime receivedAt;

    /** 发件人填写，<b>可伪造</b>，仅展示。时间判定一律用 {@link #receivedAt}。 */
    private OffsetDateTime sentAt;

    /** 规范正文；DataPurge 后置空。<b>完全由攻击者控制，不得进日志。</b> */
    @ToString.Exclude
    private String bodyText;

    /** 主要语言非中文时填入简体中文译文；DataPurge 后置空。 */
    @ToString.Exclude
    private String translatedBody;

    /** 单封摘要；DataPurge 后置空。 */
    @ToString.Exclude
    private String summary;

    private MessageCategory category;

    /** 标签 id 数组，逻辑引用 tag.id。不建关系表。写入前须排序去重。 */
    @TableField(typeHandler = LongListArrayTypeHandler.class)
    private List<Long> tags;

    /** 垃圾置信度，元数据，取值 0..1；由用户阈值映射为 spam 分类。 */
    private BigDecimal spamScore;

    /** 垃圾分类、自动分类或自动标签结果实际写入时刻；相关功能均跳过时可为 null。 */
    private OffsetDateTime classifiedAt;

    /** 自校验 DKIM 结果；outbound 为 null（不校验自己发的信）。 */
    private Boolean dkimPassed;

    /**
     * 软删除标记。<b>去重约束不含本列条件</b>——软删除的行仍占位，
     * 删除记忆必须比邮件本体活得更久，否则删掉的邮件会被下一次同步重新收进来。
     */
    private OffsetDateTime deletedAt;

    /** DataPurge 已清理正文。 */
    private Boolean purged;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
