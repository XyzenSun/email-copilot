package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.ProcessingClaim;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;

/** 租约式领取；所有变更都由 worker + fencing version + 未过期租约共同守卫。 */
public interface ProcessingClaimMapper extends BaseMapper<ProcessingClaim> {

    @Insert("""
            insert into processing_claim (message_id_pk, version)
            values (#{messageId}, 0)
            on conflict (message_id_pk) do nothing
            """)
    int insertIfAbsent(@Param("messageId") long messageId);

    @Select("""
            select progress.message_id_pk
            from processing_progress progress
            join processing_claim claim_row
              on claim_row.message_id_pk = progress.message_id_pk
            join message message_row
              on message_row.id = progress.message_id_pk
            where progress.status = 'pending'
              and progress.stage <> 'done'
              and message_row.deleted_at is null
              and message_row.purged = false
              and (claim_row.claim_until is null or claim_row.claim_until < now())
            order by progress.message_id_pk
            limit 1
            """)
    Long selectNextClaimableMessageId();

    @Select("""
            update processing_claim claim_row
            set claimed_by = #{workerId},
                claim_until = #{leaseUntil},
                version = version + 1
            where claim_row.message_id_pk = #{messageId}
              and (claim_row.claim_until is null or claim_row.claim_until < now())
              and exists (
                  select 1
                  from message message_row
                  where message_row.id = claim_row.message_id_pk
                    and message_row.deleted_at is null
                    and message_row.purged = false
              )
            returning version
            """)
    Integer claimMessage(
            @Param("messageId") long messageId,
            @Param("workerId") String workerId,
            @Param("leaseUntil") OffsetDateTime leaseUntil);

    @Update("""
            update processing_claim claim_row
            set claim_until = #{leaseUntil}
            where claim_row.message_id_pk = #{messageId}
              and claim_row.claimed_by = #{workerId}
              and claim_row.version = #{version}
              and claim_row.claim_until >= now()
              and #{leaseUntil} > now()
              and exists (
                  select 1
                  from message message_row
                  where message_row.id = claim_row.message_id_pk
                    and message_row.deleted_at is null
                    and message_row.purged = false
              )
            """)
    int renewClaim(
            @Param("messageId") long messageId,
            @Param("workerId") String workerId,
            @Param("version") int version,
            @Param("leaseUntil") OffsetDateTime leaseUntil);

    @Update("""
            update processing_claim
            set claimed_by = null,
                claim_until = null
            where message_id_pk = #{messageId}
              and claimed_by = #{workerId}
              and version = #{version}
              and claim_until >= now()
            """)
    int releaseClaim(
            @Param("messageId") long messageId,
            @Param("workerId") String workerId,
            @Param("version") int version);
}
