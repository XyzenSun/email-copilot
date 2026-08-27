package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.PendingAction;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 待审批提案。批准必须用条件更新原子消费，不是先查再改。
 *
 * <p>CAS UPDATE 不产生唯一约束冲突（只是更新 0 行），因此不会触发 PostgreSQL 事务中止
 * （与阶段7 savepoint 教训中的 INSERT 冲突不同）。零行判断用受影响行数完成。
 */
public interface PendingActionMapper extends BaseMapper<PendingAction> {

    /**
     * 原子消费批准：{@code pending → approved}。返回 1 = 消费成功，0 = 已决定/已过期/不存在。
     *
     * <p>条件 {@code approval_status='pending' AND expires_at > now()} 保证一次一用：
     * 两个并发请求只有一个能拿到 1。消费成功后同事务 INSERT action_execution(executing)。
     */
    @Update("""
            update pending_action
            set approval_status = 'approved', decided_at = now()
            where id = #{id}
              and approval_status = 'pending'
              and expires_at > now()
            """)
    int approveCas(@Param("id") long id);

    /**
     * 原子消费拒绝：{@code pending → rejected}。返回 1 = 消费成功，0 = 已决定/已过期/不存在。
     * 拒绝不创建 ActionExecution（execution 恒 null）。
     */
    @Update("""
            update pending_action
            set approval_status = 'rejected', decided_at = now()
            where id = #{id}
              and approval_status = 'pending'
              and expires_at > now()
            """)
    int rejectCas(@Param("id") long id);
}
