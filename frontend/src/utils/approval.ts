/**
 * 审批执行终态的纯函数映射（design §6.3、quality-guidelines 约束 3）。
 *
 * POST /actions/{id}/approve 永远返回 HTTP 200，业务失败是 200+execution.status。
 * 这里把 ActionExecution.status 映射为卡片执行区的 UI 展示状态，集中一处避免
 * 组件里把 failed/indeterminate 误放进 error 分支而触发重试
 * （批准一次一用、已被消费，重试只会拿到 409 PENDING_ACTION_ALREADY_DECIDED）。
 *
 * 与 utils/send.ts 的 resolveSendResult 同构——都是「200+status 三态/四态」的纯函数映射。
 */
import type { ActionExecution, ApprovalStatus } from './conversation'

/**
 * 执行区展示状态。由 resolveExecutionDisplay 从 ActionExecution.status 派生。
 *
 * 关键：failed 和 indeterminate 的 execution 不为 null——它们仍是 200 响应，
 * 要展示 resultMessage 而非进 error 分支、不重试。
 */
export interface ExecutionDisplay {
  /** 是否在执行中（executing → 转圈，禁用按钮） */
  loading: boolean
  /** 展示标签 */
  label: string
  /** 配色 tone：sage 成功 / danger 失败 / clay 不确定 / ink 中性 */
  tone: 'sage' | 'danger' | 'clay' | 'ink'
  /** resultMessage（failed/indeterminate 时展示给用户，SMTP 响应原文或错误描述） */
  resultMessage: string | null
}

/**
 * 把 ActionExecution 映射为卡片执行区的展示状态。
 *
 * @param execution approve 响应里的 execution 字段（null 表示未批准/拒绝）
 * @returns 展示状态；execution 为 null 时返回 null（不渲染执行区）
 */
export function resolveExecutionDisplay(
  execution: ActionExecution | null,
): ExecutionDisplay | null {
  if (!execution) return null
  switch (execution.status) {
    case 'executing':
      return {
        loading: true,
        label: '执行中…',
        tone: 'ink',
        resultMessage: null,
      }
    case 'succeeded':
      return {
        loading: false,
        label: '已执行',
        tone: 'sage',
        resultMessage: null,
      }
    case 'failed':
      // 关键：failed 仍是 200，展示 resultMessage，不重试
      return {
        loading: false,
        label: '执行失败',
        tone: 'danger',
        resultMessage: execution.resultMessage,
      }
    case 'indeterminate':
      // 关键：indeterminate 仍是 200，提示可能已执行，不重试
      return {
        loading: false,
        label: '结果不确定',
        tone: 'clay',
        resultMessage: execution.resultMessage,
      }
  }
}

/**
 * 判断是否显示批准/拒绝按钮：仅 pending 状态显示。
 * 已决定的（approved/rejected/expired/cancelled）不显示按钮。
 */
export function shouldShowActionButtons(status: ApprovalStatus): boolean {
  return status === 'pending'
}
