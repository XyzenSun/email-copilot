package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xyzensun.emailcopilot.domain.enums.ProcessingStage;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.ProcessingProgress;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 判定流水线阶段游标；stage 始终表示下一项待执行阶段。 */
public interface ProcessingProgressMapper extends BaseMapper<ProcessingProgress> {

    @Update("""
            update processing_progress progress
            set status = 'in_progress',
                in_progress_since = now()
            where progress.message_id_pk = #{messageId}
              and progress.stage = #{expectedStage}
              and progress.status = 'pending'
              and exists (
                  select 1
                  from processing_claim claim_row
                  join message message_row on message_row.id = claim_row.message_id_pk
                  where claim_row.message_id_pk = progress.message_id_pk
                    and claim_row.claimed_by = #{workerId}
                    and claim_row.version = #{version}
                    and claim_row.claim_until >= now()
                    and message_row.deleted_at is null
                    and message_row.purged = false
              )
            """)
    int startStage(
            @Param("messageId") long messageId,
            @Param("expectedStage") ProcessingStage expectedStage,
            @Param("workerId") String workerId,
            @Param("version") int version);

    @Update("""
            update processing_progress progress
            set stage = #{nextStage},
                status = case when #{nextStage} = 'done' then 'completed' else 'pending' end,
                in_progress_since = null,
                retry_count = 0,
                last_error_code = null,
                last_error_at = null
            where progress.message_id_pk = #{messageId}
              and progress.stage = #{expectedStage}
              and progress.status = 'in_progress'
              and exists (
                  select 1
                  from processing_claim claim_row
                  join message message_row on message_row.id = claim_row.message_id_pk
                  where claim_row.message_id_pk = progress.message_id_pk
                    and claim_row.claimed_by = #{workerId}
                    and claim_row.version = #{version}
                    and claim_row.claim_until >= now()
                    and message_row.deleted_at is null
                    and message_row.purged = false
              )
            """)
    int advanceStage(
            @Param("messageId") long messageId,
            @Param("expectedStage") ProcessingStage expectedStage,
            @Param("nextStage") ProcessingStage nextStage,
            @Param("workerId") String workerId,
            @Param("version") int version);

    @Update("""
            update processing_progress progress
            set status = case
                    when progress.retry_count + 1 > #{retryLimit} then 'failed'
                    else 'pending'
                end,
                in_progress_since = null,
                retry_count = progress.retry_count + 1,
                last_error_code = #{errorCode},
                last_error_at = now()
            where progress.message_id_pk = #{messageId}
              and progress.stage = #{expectedStage}
              and progress.status = 'in_progress'
              and exists (
                  select 1
                  from processing_claim claim_row
                  join message message_row on message_row.id = claim_row.message_id_pk
                  where claim_row.message_id_pk = progress.message_id_pk
                    and claim_row.claimed_by = #{workerId}
                    and claim_row.version = #{version}
                    and claim_row.claim_until >= now()
                    and message_row.deleted_at is null
                    and message_row.purged = false
              )
            """)
    int markFailure(
            @Param("messageId") long messageId,
            @Param("expectedStage") ProcessingStage expectedStage,
            @Param("workerId") String workerId,
            @Param("version") int version,
            @Param("errorCode") String errorCode,
            @Param("retryLimit") int retryLimit);

    @Update("""
            update processing_progress progress
            set status = 'pending',
                in_progress_since = null
            where progress.status = 'in_progress'
              and exists (
                  select 1
                  from processing_claim claim_row
                  join message message_row on message_row.id = claim_row.message_id_pk
                  where claim_row.message_id_pk = progress.message_id_pk
                    and claim_row.claim_until < now()
                    and message_row.deleted_at is null
                    and message_row.purged = false
              )
            """)
    int recoverExpiredInProgress();
}
