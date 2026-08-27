package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.ActionExecution;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 执行结果。pending_action_id 作主键保证一次批准只有一次执行。
 *
 * <p>{@code insertExecuting} 在批准消费的<b>同一事务</b>内调用（步骤1）；
 * {@code updateTerminal} 在 SMTP 提交后的<b>新事务</b>内调用（步骤2）。
 * PK = IdType.INPUT 保证一次批准一次执行，不自动第二次。
 */
public interface ActionExecutionMapper extends BaseMapper<ActionExecution> {

    /**
     * 步骤1：同事务插入 executing 状态行。PK = pending_action_id，重复插入会被主键拒绝
     * （一次批准一次执行的结构保证）。
     */
    @Update("""
            insert into action_execution (pending_action_id, status, started_at)
            values (#{pendingActionId}, 'executing', now())
            """)
    int insertExecuting(@Param("pendingActionId") long pendingActionId);

    /**
     * 步骤2：记录终态（succeeded/failed/indeterminate），写 finished_at 与 result_message。
     *
     * @param statusValue 终态字面值（{@code succeeded}/{@code failed}/{@code indeterminate}）
     */
    @Update("""
            update action_execution
            set status = #{statusValue},
                finished_at = now(),
                result_message = #{resultMessage}
            where pending_action_id = #{pendingActionId}
            """)
    int updateTerminal(
            @Param("pendingActionId") long pendingActionId,
            @Param("statusValue") String statusValue,
            @Param("resultMessage") String resultMessage);
}
