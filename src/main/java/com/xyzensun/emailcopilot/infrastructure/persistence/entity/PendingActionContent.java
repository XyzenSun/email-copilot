package com.xyzensun.emailcopilot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xyzensun.emailcopilot.domain.AttachmentMeta;
import com.xyzensun.emailcopilot.domain.Recipients;
import com.xyzensun.emailcopilot.infrastructure.persistence.handler.AttachmentMetaListJsonbTypeHandler;
import com.xyzensun.emailcopilot.infrastructure.persistence.handler.RecipientsJsonbTypeHandler;
import lombok.Data;
import lombok.ToString;

import java.util.List;

/**
 * SEND_EMAIL 与 SAVE_DRAFT 的<b>不可变内容快照</b>，与 {@link PendingAction} 1:1。
 * {@code DATABASE.md} §5.5。
 *
 * <p><b>执行只读此表，不读 {@link Draft}。</b>这是硬约束：若执行时读取草稿的最新版本，
 * "批准后再改草稿"就成了绕过审批改邮件内容的后门。{@code draft} 可在审批后被继续编辑，
 * 不影响此快照。
 *
 * <p><b>保持独立表而不合入 pending_action</b>：合表后 LOCAL_DELETE 的行会有四个恒为 NULL
 * 的字段、需要三条 check 约束防止填错；"这份提案是发信"由"有没有这一行"表达比由
 * "四个字段是否为空"表达更清晰，且待审批列表页查询不必拖着可能几 KB 的正文。
 *
 * <p>不设 {@code createdAt}：与 {@link PendingAction} 1:1 且同事务创建，时刻完全相同。
 */
@Data
@TableName(value = "pending_action_content", autoResultMap = true)
public class PendingActionContent {

    /** 逻辑引用 pending_action，由应用指定，不自增。 */
    @TableId(type = IdType.INPUT)
    private Long pendingActionId;

    /** 逻辑引用 mail_account。发信账号决定署名与发信通道。 */
    private Long fromMailAccountId;

    /**
     * 逻辑引用 message；回复时填，新建邮件为 null。
     *
     * <p><b>这是真回复的前提</b>：一封回复邮件必须带 {@code In-Reply-To} 与
     * {@code References} 头指向原邮件的 Message-ID，否则收件人的邮件客户端不会把它
     * 接进原会话，而是当成主题带 {@code Re:} 的孤立新邮件——本系统自己的会话归并（JWZ）
     * 依据的正是这两个头。
     *
     * <p>发信时由代码读出该 message 的 Message-ID 作 {@code In-Reply-To}，
     * 并把它追加到该邮件已有的引用链后面组成 {@code References}；
     * <b>不接受模型自行拼头。</b>
     */
    private Long inReplyToMessageId;

    /**
     * 收件人列表。<b>地址格式由应用在创建提案时校验</b>（JSONB 无法约束），
     * 不得相信模型已校验过目标地址。
     */
    @TableField(typeHandler = RecipientsJsonbTypeHandler.class)
    private Recipients recipients;

    private String subject;

    /** 不可变快照。 */
    @ToString.Exclude
    private String bodyText;

    /**
     * 附件元数据占位，<b>第一阶段恒为空数组</b>。
     * 实现时不得据此认为发信支持附件。
     */
    @TableField(typeHandler = AttachmentMetaListJsonbTypeHandler.class)
    private List<AttachmentMeta> attachmentMeta;
}
