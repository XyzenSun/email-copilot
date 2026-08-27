package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xyzensun.emailcopilot.domain.enums.EvidenceSource;
import com.xyzensun.emailcopilot.domain.enums.EvidenceTargetType;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.TurnReadEvidence;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 本轮读取过的邮件或会话。<b>由代码写入，不由 AI 自述。</b>
 *
 * <p><b>本表是复合主键 {@code (turn_id, target_type, target_id)}，
 * MyBatis-Plus 不支持复合主键</b>，实体因此没有 {@code @TableId}：
 * {@code insert} 与条件查询可用，by-id 方法不可用。本表只按 turnId 成组查询。
 *
 * <p>写入需要 {@code ON CONFLICT} 语义（同一目标可能先被检索命中、随后又被直接读），
 * 而 {@code BaseMapper.insert} 生成的是普通 INSERT。自定义 {@link #upsertSource} 方法实现：
 * 新途径为 {@code direct_read} 时覆盖 {@code source}（主动点开是更强的信号），否则忽略。
 */
public interface TurnReadEvidenceMapper extends BaseMapper<TurnReadEvidence> {

    /**
     * 插入读取证据，按复合主键去重。
     *
     * <p>新来源为 {@code DIRECT_READ} 时覆盖已有 {@code source}（用户主动点开是更强的信号），
     * 否则忽略冲突（保持首次写入的 source）。这保证前端拿到的清单天然不含重复项，
     * 且优先级最高的途径胜出。
     *
     * @return 受影响行数（1 = 新插入或覆盖，0 = 忽略冲突）
     */
    @Insert("""
            insert into turn_read_evidence (turn_id, target_type, target_id, source)
            values (#{turnId}, #{targetType}, #{targetId}, #{source})
            on conflict (turn_id, target_type, target_id)
            do update set source = excluded.source
            where turn_read_evidence.source <> 'direct_read'
            """)
    int upsertSource(
            @Param("turnId") Long turnId,
            @Param("targetType") EvidenceTargetType targetType,
            @Param("targetId") Long targetId,
            @Param("source") EvidenceSource source);
}
