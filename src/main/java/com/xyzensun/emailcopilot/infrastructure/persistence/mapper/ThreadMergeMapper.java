package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** JWZ 归并的集合查询与有序锁；全库无 FK，因此跨表关联在这些显式 SQL 中维护。 */
public interface ThreadMergeMapper {

    /**
     * 同时查“这些 Message-ID 已经对应哪些消息”和“哪些消息曾引用这些 ID”。
     * 后者让被引用消息乱序后到时，能够找回先前独立存在的回复会话。
     */
    @Select("""
            <script>
            select distinct message_row.thread_node_id
            from message message_row
            where message_row.message_id in
              <foreach collection="rfcMessageIds" item="id" open="(" separator="," close=")">
                #{id}
              </foreach>
               or exists (
                   select 1
                   from message_mention mention
                   where mention.message_id_pk = message_row.id
                     and mention.referenced_rfc_message_id in
                       <foreach collection="rfcMessageIds" item="id" open="(" separator="," close=")">
                         #{id}
                       </foreach>
               )
            order by message_row.thread_node_id
            </script>
            """)
    List<Long> selectCandidateRepresentativeIds(
            @Param("rfcMessageIds") List<String> rfcMessageIds);

    /**
     * BaseSubject 只补充没有引用链关联的根候选，并要求当前或既有一方带回复前缀。
     * 这样能让“主题 / Re: 主题”的孤立根进入同一次 Threader 重算，同时不会把两封
     * 同主题但都不是回复的独立新邮件直接归并。
     */
    @Select("""
            select distinct thread_node_id
            from message
            where deleted_at is null
              and base_subject = #{baseSubject}
              and (#{currentSubjectIsReply} = true
                   or lower(ltrim(coalesce(subject, ''))) like 're:%'
                   or lower(ltrim(coalesce(subject, ''))) like 'fw:%'
                   or lower(ltrim(coalesce(subject, ''))) like 'fwd:%')
            order by thread_node_id
            """)
    List<Long> selectRootFallbackRepresentativeIds(
            @Param("baseSubject") String baseSubject,
            @Param("currentSubjectIsReply") boolean currentSubjectIsReply);

    /**
     * 同一 BaseSubject 的根集兜底没有天然共享行；事务级 advisory lock 防止两个
     * 首次出现的“主题 / Re: 主题”并发查询时互相不可见。不同主题使用不同锁键。
     */
    @Select("select pg_advisory_xact_lock(hashtextextended(#{baseSubject}, 0))::text")
    String lockRootFallbackSubject(@Param("baseSubject") String baseSubject);

    /**
     * 锁定引用链节点本身，而不只是已有 message 行。
     *
     * <p>被引用邮件尚未到达时，多个跨账号回复没有共同的 message 行可锁；若只锁
     * representative，它们可能各自完成并永久分裂。按节点主键升序加锁，让共享
     * placeholder 成为这类乱序归并的串行化点，也保持多节点引用之间的锁顺序一致。
     */
    @Select("""
            <script>
            select id
            from thread_node
            where rfc_message_id in
              <foreach collection="rfcMessageIds" item="id" open="(" separator="," close=")">
                #{id}
              </foreach>
            order by id
            for update
            </script>
            """)
    List<Long> lockThreadNodesByRfcMessageIds(
            @Param("rfcMessageIds") List<String> rfcMessageIds);

    /** 固定升序取行锁，避免两个并发回复按相反顺序锁多个 representative。 */
    @Select("""
            <script>
            select id
            from message
            where thread_node_id in
              <foreach collection="representativeIds" item="id" open="(" separator="," close=")">
                #{id}
              </foreach>
            order by thread_node_id, id
            for update
            </script>
            """)
    List<Long> lockMessagesByRepresentativeIds(
            @Param("representativeIds") List<Long> representativeIds);

    @Select("""
            <script>
            select count(*)
            from message
            where deleted_at is null
              and thread_node_id in
                <foreach collection="representativeIds" item="id" open="(" separator="," close=")">
                  #{id}
                </foreach>
            </script>
            """)
    long countActiveMessagesByRepresentativeIds(
            @Param("representativeIds") List<Long> representativeIds);

    @Update("""
            <script>
            update message
            set thread_node_id = #{representativeId}
            where id in
              <foreach collection="messageIds" item="id" open="(" separator="," close=")">
                #{id}
              </foreach>
              and thread_node_id &lt;&gt; #{representativeId}
            </script>
            """)
    int updateRepresentative(
            @Param("messageIds") List<Long> messageIds,
            @Param("representativeId") long representativeId);
}
