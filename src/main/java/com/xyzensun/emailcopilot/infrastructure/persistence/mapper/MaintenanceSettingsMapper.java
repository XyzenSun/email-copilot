package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 账号物理删除的专用 SQL。
 *
 * <p>全库没有 FK，删除顺序必须在应用事务中明确表达；这些语句只按逻辑引用列删除，
 * 不使用无条件全表操作。
 *
 * <p>阶段11 起 DataPurge 批次方法（selectPurgePreview / purgeNextBatch）已移除——
 * 删除时同步清正文，库内无 purge 目标。
 */
public interface MaintenanceSettingsMapper {

    /** 仍未决定的发信/存草稿提案保留历史，但账号消失后必须转 cancelled。 */
    @Update("""
            update pending_action pending
            set approval_status = 'cancelled',
                decided_at = now(),
                cancel_reason = '发信账号已被移除'
            where pending.approval_status = 'pending'
              and exists (
                  select 1
                  from pending_action_content content
                  where content.pending_action_id = pending.id
                    and content.from_mail_account_id = #{mailAccountId}
              )
            """)
    int cancelPendingContentActions(@Param("mailAccountId") long mailAccountId);

    /**
     * 从其它账号发出的回复也可能引用即将物理删除的邮件；缺少原邮件后无法安全构造 References。
     */
    @Update("""
            update pending_action pending
            set approval_status = 'cancelled',
                decided_at = now(),
                cancel_reason = '回复目标所属账号已被移除'
            where pending.approval_status = 'pending'
              and exists (
                  select 1
                  from pending_action_content content
                  join message message_row on message_row.id = content.in_reply_to_message_id
                  where content.pending_action_id = pending.id
                    and message_row.mail_account_id = #{mailAccountId}
              )
            """)
    int cancelPendingReplyActions(@Param("mailAccountId") long mailAccountId);

    /** 本地删除提案的目标邮件被账号删除物理移除时，同样不能继续批准。 */
    @Update("""
            update pending_action pending
            set approval_status = 'cancelled',
                decided_at = now(),
                cancel_reason = '目标邮件所属账号已被移除'
            where pending.approval_status = 'pending'
              and pending.action_type = 'local_delete'
              and exists (
                  select 1
                  from message message_row
                  where message_row.mail_account_id = #{mailAccountId}
                    and message_row.id = any(pending.target_message_ids)
              )
            """)
    int cancelPendingLocalDeleteActions(@Param("mailAccountId") long mailAccountId);

    @Delete("""
            delete from message_source child
            using message parent
            where child.message_id_pk = parent.id
              and parent.mail_account_id = #{mailAccountId}
            """)
    int deleteMessageSources(@Param("mailAccountId") long mailAccountId);

    @Delete("""
            delete from processing_progress child
            using message parent
            where child.message_id_pk = parent.id
              and parent.mail_account_id = #{mailAccountId}
            """)
    int deleteProcessingProgress(@Param("mailAccountId") long mailAccountId);

    @Delete("""
            delete from processing_claim child
            using message parent
            where child.message_id_pk = parent.id
              and parent.mail_account_id = #{mailAccountId}
            """)
    int deleteProcessingClaims(@Param("mailAccountId") long mailAccountId);

    @Delete("""
            delete from attachment child
            using message parent
            where child.message_id_pk = parent.id
              and parent.mail_account_id = #{mailAccountId}
            """)
    int deleteAttachments(@Param("mailAccountId") long mailAccountId);

    @Delete("""
            delete from message_mention child
            using message parent
            where child.message_id_pk = parent.id
              and parent.mail_account_id = #{mailAccountId}
            """)
    int deleteMessageMentions(@Param("mailAccountId") long mailAccountId);

    @Delete("delete from draft where from_mail_account_id = #{mailAccountId}")
    int deleteDrafts(@Param("mailAccountId") long mailAccountId);

    @Delete("delete from external_account_secret where mail_account_id = #{mailAccountId}")
    int deleteSecrets(@Param("mailAccountId") long mailAccountId);

    @Delete("delete from message where mail_account_id = #{mailAccountId}")
    int deleteMessages(@Param("mailAccountId") long mailAccountId);

    /** 行已在事务开头锁住，条件仍写在 DELETE 上作为最后一道不可逆操作守卫。 */
    @Delete("""
            delete from mail_account
            where id = #{mailAccountId}
              and imap_enabled = false
              and smtp_enabled = false
            """)
    int deleteDisabledAccount(@Param("mailAccountId") long mailAccountId);
}
