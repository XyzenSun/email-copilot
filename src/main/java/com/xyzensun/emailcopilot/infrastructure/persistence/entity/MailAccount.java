package com.xyzensun.emailcopilot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xyzensun.emailcopilot.infrastructure.persistence.handler.StringListJsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 邮箱账号接入配置（{@code DATABASE.md} §3.1）。一个账号同时持有收信（IMAP）
 * 与发信（SMTP）配置。
 *
 * <p>用户名明文保存（非凭据）；密码归 {@link ExternalAccountSecret} 加密保存。
 *
 * <p><b>没有 enabled 总开关列</b>：两个通道各自的 {@code *Enabled} 已表达全部状态，
 * "停用账号"就是把两个都置 false。多一个总开关会产生"总开关关了但 imapEnabled 还是 true"
 * 这类无意义组合，且每处判断都要同时看两层。
 *
 * <p><b>删除账号的前置条件是已停用</b>，且删除时连带物理删除该账号的全部邮件。
 * 这不是级联删除的便利问题，而是把一个不可逆操作拆成两个明确步骤：停用先切断收信、
 * 留出反悔时间，此时邮件仍可读；确认要清空时再删。若允许直接删除启用中的账号，
 * 一次点击就同时切断收信并销毁数万封邮件。
 */
@Data
@TableName(value = "mail_account", autoResultMap = true)
public class MailAccount {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 账号规范邮箱地址，列表展示与发信署名回退用它。 */
    private String emailAddress;

    /** 展示名，兼作发信署名。一个账号一个署名够用。 */
    private String displayName;

    private String imapHost;

    private Integer imapPort;

    /** IMAP 登录用户名，明文，<b>非凭据</b>。 */
    private String imapUsername;

    /** 同步文件夹列表。用 JSONB 是因为它只读、结构简单、无查询价值。 */
    @TableField(typeHandler = StringListJsonbTypeHandler.class)
    private List<String> imapFolders;

    private Boolean imapEnabled;

    private String smtpHost;

    private Integer smtpPort;

    /** SMTP 登录用户名，明文，<b>非凭据</b>。 */
    private String smtpUsername;

    private Boolean smtpEnabled;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
