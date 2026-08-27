package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xyzensun.emailcopilot.application.processing.ProcessingStageResult;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Message;
import com.xyzensun.emailcopilot.infrastructure.persistence.handler.RecipientsJsonbTypeHandler;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 邮件，收到的与自己发出的同表。去重靠唯一约束而非先查再插。
 */
public interface MessageMapper extends BaseMapper<Message> {

    /**
     * 直接 INSERT 并让数据库两个唯一键裁决 FirstIngestWins。返回 null 表示命中任一去重键；
     * ON CONFLICT 避免 PostgreSQL 把当前事务标成 aborted，调用方才能在同一事务补来源元数据。
     */
    @Select("""
            insert into message
                (mail_account_id, direction, message_id, fingerprint, thread_node_id,
                 from_display, from_address, from_address_domain, from_authenticated_domain,
                 recipients, subject, base_subject, received_at, sent_at, body_text,
                 dkim_passed)
            values
                (#{message.mailAccountId}, #{message.direction}, #{message.messageId},
                 #{message.fingerprint}, #{message.threadNodeId}, #{message.fromDisplay},
                 #{message.fromAddress}, #{message.fromAddressDomain},
                 #{message.fromAuthenticatedDomain},
                 #{message.recipients,jdbcType=OTHER,typeHandler=com.xyzensun.emailcopilot.infrastructure.persistence.handler.RecipientsJsonbTypeHandler},
                 #{message.subject}, #{message.baseSubject}, #{message.receivedAt},
                 #{message.sentAt}, #{message.bodyText}, #{message.dkimPassed})
            on conflict do nothing
            returning id
            """)
    Long insertInboundIfAbsent(@Param("message") Message message);

    /**
     * 发信成功后 outbound 邮件入库。不设 fingerprint/category/spam_score/dkim_passed
     * （满足 {@code ck_message_outbound_no_judgment}），不建 processing_progress/processing_claim。
     *
     * <p>ON CONFLICT DO NOTHING 处理服务商把副本放进 Sent 且 IMAP 后读到的场景
     * （{@code uk(mail_account_id, message_id)} 挡住重复）。返回 null 表示已存在，
     * 调用方走 FirstIngestWins 只补 message_source。
     */
    @Select("""
            insert into message
                (mail_account_id, direction, message_id, thread_node_id,
                 from_display, from_address, from_address_domain,
                 recipients, subject, base_subject, received_at, sent_at, body_text)
            values
                (#{message.mailAccountId}, 'outbound', #{message.messageId},
                 #{message.threadNodeId}, #{message.fromDisplay},
                 #{message.fromAddress}, #{message.fromAddressDomain},
                 #{message.recipients,jdbcType=OTHER,typeHandler=com.xyzensun.emailcopilot.infrastructure.persistence.handler.RecipientsJsonbTypeHandler},
                 #{message.subject}, #{message.baseSubject}, #{message.receivedAt},
                 #{message.sentAt}, #{message.bodyText})
            on conflict do nothing
            returning id
            """)
    Long insertOutbound(@Param("message") Message message);

    @Select("""
            select id
            from message
            where mail_account_id = #{mailAccountId}
              and (message_id = #{messageId}
                   or (cast(#{fingerprint} as text) is not null
                       and fingerprint = cast(#{fingerprint} as text)))
            order by id
            limit 1
            """)
    Long selectExistingIdByDedupeKey(
            @Param("mailAccountId") long mailAccountId,
            @Param("messageId") String messageId,
            @Param("fingerprint") String fingerprint);

    /**
     * 阶段业务列写回的统一 fencing SQL。后续游标推进若失败，应用服务事务会回滚本更新。
     */
    @Update("""
            update message message_row
            set spam_score = case
                    when #{result.writeSpamScore} then #{result.spamScore}
                    else message_row.spam_score
                end,
                category = case
                    when #{result.writeCategory} then #{result.category}
                    else message_row.category
                end,
                tags = case
                    when #{result.writeTags} then
                        #{result.tagIds,jdbcType=ARRAY,typeHandler=com.xyzensun.emailcopilot.infrastructure.persistence.handler.LongListArrayTypeHandler}
                    else message_row.tags
                end,
                translated_body = case
                    when #{result.writeTranslation} then #{result.translatedBody}
                    else message_row.translated_body
                end,
                summary = case
                    when #{result.writeSummary} then #{result.summary}
                    else message_row.summary
                end,
                classified_at = case
                    when #{result.markClassified} then now()
                    else message_row.classified_at
                end
            where message_row.id = #{messageId}
              and message_row.deleted_at is null
              and message_row.purged = false
              and exists (
                  select 1
                  from processing_claim claim_row
                  where claim_row.message_id_pk = message_row.id
                    and claim_row.claimed_by = #{workerId}
                    and claim_row.version = #{version}
                    and claim_row.claim_until >= now()
              )
            """)
    int applyProcessingStageResult(
            @Param("messageId") long messageId,
            @Param("workerId") String workerId,
            @Param("version") int version,
            @Param("result") ProcessingStageResult result);

    /**
     * 软删 + 同步清正文 + 标 purged，一条 UPDATE（阶段11 方案 C）。
     *
     * <p>把原 DataPurge 的清正文逻辑并进删除动作：deleted_at 写值的同时清空
     * body_text/translated_body/summary 并标 purged=true，库内不产生
     * 「deleted_at 有值但 purged=false」的中间态，DataPurge 失去目标。
     * 只对 deleted_at IS NULL 的行生效（已删的不重复清）。返回实际命中的行数。
     *
     * <p>骨架（message_id/thread_node_id/deleted_at）保留 → 继续占 uk 位，
     * 防 IMAP 重同步复活（DATABASE.md §3.3「删除记忆必须比邮件活得更久」）。
     */
    @Update("""
            <script>
            update message
               set deleted_at = now(),
                   body_text = null,
                   translated_body = null,
                   summary = null,
                   purged = true
             where id in
               <foreach collection="ids" item="id" open="(" separator="," close=")">
                 #{id}
               </foreach>
               and deleted_at is null
            </script>
            """)
    int deleteAndPurgeInOneGo(@Param("ids") List<Long> ids);

    /**
     * 阶段15 自动清理：选出早于 cutoff 的 inbound 邮件 id（含阶段11 软删骨架一并真删整行）。
     *
     * <p>不叠加 {@code deleted_at is null} 过滤：retention 的语义是按时间真删老邮件释放空间，
     * 旧骨架同样该清；增量同步只拉水位之后的新 UID，不会重拉这些老邮件复活
     * （见 design「不复活依据」）。outbound 不在此查询内，发件历史保留。
     */
    @Select("""
            select id
              from message
             where direction = 'inbound'
               and received_at < #{cutoff}
            """)
    List<Long> selectInboundIdsOlderThan(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * 阶段15 自动清理：真删整行（DELETE FROM message）。与阶段11 {@link #deleteAndPurgeInOneGo}
     * 的软删留骨架语义不同、并存——本方法直接物理删除，释放空间，不可逆。
     *
     * <p><b>方法名必须避开 {@code deleteByIds}</b>：MyBatis-Plus {@code BaseMapper.deleteByIds}
     * 是 default 方法，若本接口定义同名方法会覆盖它；{@code deleteBatchIds} 经 default 链路
     * 转发到同名方法时参数绑定不含 {@code ids}，foreach 求值为 null 直接抛 BuilderException
     * （曾致 MailSearchServiceTest/SearchIndexLifecycleTest 的 @AfterEach 清理连锁失败）。
     */
    @Delete("""
            <script>
            delete from message
             where id in
               <foreach collection="ids" item="id" open="(" separator="," close=")">
                 #{id}
               </foreach>
            </script>
            """)
    int deleteByIdList(@Param("ids") List<Long> ids);
}
