package com.xyzensun.emailcopilot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 一个邮箱账号在一个 IMAP mailbox 中的增量游标与租约。
 *
 * <p>UID 和 UIDVALIDITY 都是 mailbox 作用域的控制状态；把它们放到账号表
 * 会让两个 mailbox 的相同 UID 互相覆盖，重同步时可能静默漏信。远程 IMAP
 * I/O 不在本实体的事务中进行，领取和推进只通过 Mapper 的短 CAS 操作完成。
 */
@Data
@TableName("imap_folder_cursor")
public class ImapFolderCursor {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 逻辑引用 mail_account。全库不建外键。 */
    private Long mailAccountId;

    /** 服务端返回的完整 mailbox 名，不保存 inbox/junk 角色。 */
    private String folderName;

    /** 首次建立该 mailbox 时固定的 INTERNALDATE 边界。 */
    private OffsetDateTime initialSyncSince;

    /** 当前 mailbox 的 UIDVALIDITY，数据库范围为 1..4294967295。 */
    private Long uidValidity;

    /** 已达到终态的最大 UID；0 表示尚未完成 bootstrap。 */
    private Long lastSeenUid;

    /** 当前持有 mailbox 租约的执行者标识。 */
    private String claimedBy;

    /** 租约到期时刻；远程 I/O 期间不持有数据库行锁。 */
    private OffsetDateTime claimUntil;

    /** 领取、重置和推进共用的乐观锁版本。 */
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime updatedAt;
}
