package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.ImapFolderCursor;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;

/**
 * IMAP mailbox 级游标的短事务 CAS 操作。
 *
 * <p>远程 IMAP I/O 期间不持有行锁。所有写操作都带执行者/版本守卫，旧执行者
 * 租约过期后即使稍晚返回，也不能覆盖新执行者已经推进的水位。
 */
public interface ImapFolderCursorMapper extends BaseMapper<ImapFolderCursor> {

    @Select("""
            select *
            from imap_folder_cursor
            where mail_account_id = #{mailAccountId}
              and folder_name = #{folderName}
            """)
    ImapFolderCursor selectByAccountAndFolder(
            @Param("mailAccountId") long mailAccountId,
            @Param("folderName") String folderName);

    /**
     * 并发首次发现依靠唯一约束决定胜者。受影响 0 行表示另一执行者已经创建，
     * 调用方随后重读数据库事实源，不使用“先查再插”承担正确性。
     */
    @Insert("""
            insert into imap_folder_cursor
                (mail_account_id, folder_name, initial_sync_since, uid_validity,
                 last_seen_uid, version, created_at, updated_at)
            values
                (#{mailAccountId}, #{folderName},
                 now() - make_interval(days => #{initialSyncDays}), #{uidValidity},
                 0, 0, now(), now())
            on conflict (mail_account_id, folder_name) do nothing
            """)
    int insertIfAbsent(
            @Param("mailAccountId") long mailAccountId,
            @Param("folderName") String folderName,
            @Param("uidValidity") long uidValidity,
            @Param("initialSyncDays") int initialSyncDays);

    /** 租约为空或已经过期时才可领取；领取本身递增 version。 */
    @Update("""
            update imap_folder_cursor
            set claimed_by = #{workerId},
                claim_until = #{leaseUntil},
                version = version + 1,
                updated_at = now()
            where id = #{cursorId}
              and (claim_until is null or claim_until < now())
            """)
    int claimCursor(
            @Param("cursorId") long cursorId,
            @Param("workerId") String workerId,
            @Param("leaseUntil") OffsetDateTime leaseUntil);

    /** 仅当前执行者和预期版本可续租，防止旧执行者把已接管的租约延长。 */
    @Update("""
            update imap_folder_cursor
            set claim_until = #{leaseUntil},
                version = version + 1,
                updated_at = now()
            where id = #{cursorId}
              and claimed_by = #{workerId}
              and version = #{expectedVersion}
              and claim_until >= now()
            """)
    int renewClaim(
            @Param("cursorId") long cursorId,
            @Param("workerId") String workerId,
            @Param("expectedVersion") int expectedVersion,
            @Param("leaseUntil") OffsetDateTime leaseUntil);

    /**
     * 一封消息达到终态后推进水位。必须同时持有未过期租约、匹配 UIDVALIDITY
     * 和预期版本，且 completedUid 严格大于旧水位，因此永不倒退。
     */
    @Update("""
            update imap_folder_cursor
            set last_seen_uid = #{completedUid},
                version = version + 1,
                updated_at = now()
            where id = #{cursorId}
              and claimed_by = #{workerId}
              and uid_validity = #{expectedUidValidity}
              and version = #{expectedVersion}
              and claim_until >= now()
              and last_seen_uid < #{completedUid}
            """)
    int advanceCursor(
            @Param("cursorId") long cursorId,
            @Param("workerId") String workerId,
            @Param("expectedUidValidity") long expectedUidValidity,
            @Param("expectedVersion") int expectedVersion,
            @Param("completedUid") long completedUid);

    /**
     * UIDVALIDITY 改变时保留固定 initial_sync_since，仅废弃旧 UID 命名空间并归零水位。
     */
    @Update("""
            update imap_folder_cursor
            set uid_validity = #{newUidValidity},
                last_seen_uid = 0,
                version = version + 1,
                updated_at = now()
            where id = #{cursorId}
              and claimed_by = #{workerId}
              and version = #{expectedVersion}
              and claim_until >= now()
              and uid_validity <> #{newUidValidity}
            """)
    int resetCursorForUidValidityChange(
            @Param("cursorId") long cursorId,
            @Param("workerId") String workerId,
            @Param("expectedVersion") int expectedVersion,
            @Param("newUidValidity") long newUidValidity);

    /** 完成或确定失败后只允许租约持有者释放自己的租约。 */
    @Update("""
            update imap_folder_cursor
            set claimed_by = null,
                claim_until = null,
                version = version + 1,
                updated_at = now()
            where id = #{cursorId}
              and claimed_by = #{workerId}
              and version = #{expectedVersion}
              and claim_until >= now()
            """)
    int releaseClaim(
            @Param("cursorId") long cursorId,
            @Param("workerId") String workerId,
            @Param("expectedVersion") int expectedVersion);

    @Delete("delete from imap_folder_cursor where mail_account_id = #{mailAccountId}")
    int deleteByMailAccountId(@Param("mailAccountId") long mailAccountId);
}
