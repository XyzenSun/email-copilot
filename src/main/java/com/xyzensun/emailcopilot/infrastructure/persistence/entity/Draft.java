package com.xyzensun.emailcopilot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
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

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 可选的本地草稿，独立于发送审批，<b>可被用户继续编辑</b>（{@code DATABASE.md} §5.7）。
 *
 * <p><b>审批只约束 AI 的写入路径</b>：SAVE_DRAFT 提案批准后由系统写本表；
 * 而用户自己在界面上新建或编辑草稿直接走 {@code POST}/{@code PATCH /api/drafts}，
 * <b>不经审批</b>。闸门是为 AI 设的（它的上下文里混着攻击者可控的邮件正文），
 * 用户自己点保存就是用户的意图，再确认一次只会稀释审批的严肃性。
 *
 * <p>同理用户点发送走 {@code POST /api/send} 直达 SMTP，不创建 PendingAction；
 * <b>对应的发信服务从不注册为 AI 工具</b>，因此这不构成绕过闸门的途径。
 *
 * <p>发送审批快照来自 {@link PendingActionContent}，<b>不读本表</b>；
 * 用户直接发信的内容也来自请求体而非 draftId——发出去的必须是用户屏幕上那份。
 */
@Data
@TableName(value = "draft", autoResultMap = true)
public class Draft {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 逻辑引用 conversation，可空。
     *
     * <p>草稿有两个来源：在 AI 对话里让它起草（填对话 id），
     * 或在邮件会话页直接写回复（为 null，靠 {@link #inReplyToMessageId} 定位）。
     * 两者皆空即从零新建的邮件，{@code GET /api/drafts} 是这类草稿唯一的入口。
     */
    private Long conversationId;

    /** 逻辑引用 message；回复某封邮件时填，新建邮件为 null。 */
    private Long inReplyToMessageId;

    /** 逻辑引用 mail_account。发信账号决定署名与发信通道。 */
    private Long fromMailAccountId;

    @TableField(typeHandler = RecipientsJsonbTypeHandler.class)
    private Recipients recipients;

    /** 允许空字符串（写一半就存）。 */
    private String subject;

    /** 允许空字符串。 */
    @ToString.Exclude
    private String bodyText;

    /** 附件元数据占位，<b>第一阶段恒为空数组</b>。 */
    @TableField(typeHandler = AttachmentMetaListJsonbTypeHandler.class)
    private List<AttachmentMeta> attachmentMeta;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
