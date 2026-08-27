/**
 * 对话与审批相关的共享类型别名 + 纯函数辅助。
 *
 * 集中放类型别名（从 types.gen.ts 的 components['schemas'] 派生），让组件
 * 用可读的短名而非 `components['schemas']['ConversationDetail']`。
 * 同样的模式见 utils/mail.ts。code-reuse-thinking-guide：类型别名单一定义点。
 */
import type { components } from '@/api/types.gen'

// ── 对话 ──
export type ConversationSummary = components['schemas']['ConversationSummary']
export type ConversationDetail = components['schemas']['ConversationDetail']
export type ConversationContext = components['schemas']['ConversationContext']
export type ConversationUpdateRequest = components['schemas']['ConversationUpdateRequest']
export type ConversationPage = components['schemas']['ConversationPage']

// ── 轮次 ──
export type Turn = components['schemas']['Turn']
export type TurnStatus = components['schemas']['TurnStatus']
export type TurnRequest = components['schemas']['TurnRequest']
export type ReadEvidence = components['schemas']['ReadEvidence']
export type EvidenceSource = components['schemas']['EvidenceSource']

// ── 审批 ──
export type PendingActionCard = components['schemas']['PendingActionCard']
export type PendingActionDetail = components['schemas']['PendingActionDetail']
export type PendingActionContent = components['schemas']['PendingActionContent']
export type ActionTarget = components['schemas']['ActionTarget']
export type ActionExecution = components['schemas']['ActionExecution']
export type ApprovalResult = components['schemas']['ApprovalResult']
export type ApprovalStatus = components['schemas']['ApprovalStatus']
export type ActionType = components['schemas']['ActionType']
export type ExecutionStatus = components['schemas']['ExecutionStatus']
export type PendingActionPage = components['schemas']['PendingActionPage']

// ── SSE 事件 data 结构（不出现在任何响应体里，仅供解析用） ──
export type SseStartEvent = components['schemas']['SseStartEvent']
export type SseTokenEvent = components['schemas']['SseTokenEvent']
export type SseActionEvent = components['schemas']['SseActionEvent']
export type SseErrorEvent = components['schemas']['SseErrorEvent']
export type SseCompactedEvent = components['schemas']['SseCompactedEvent']
export type SseDoneEvent = components['schemas']['SseDoneEvent']
export type SseDraftEvent = components['schemas']['SseDraftEvent']

/**
 * ActionType 的中文展示名。卡片顶部标签用，零件渲染按 actionType 分支。
 * 绝不取 AI 自述——这是安全决策依据（quality-guidelines 约束 5）。
 */
export const ACTION_TYPE_LABELS: Record<ActionType, string> = {
  send_email: '发送邮件',
  save_draft: '保存草稿',
  local_delete: '本地删除',
}

/**
 * ApprovalStatus 的中文展示名 + 灰显标记。
 * cancelled（系统判定无法执行）与 rejected（用户主动拒绝）语义不同，展示要区分。
 */
export const APPROVAL_STATUS_META: Record<
  ApprovalStatus,
  { label: string; grayed: boolean; tone: 'sage' | 'clay' | 'ink' | 'danger' }
> = {
  pending: { label: '未审批', grayed: false, tone: 'clay' },
  approved: { label: '已批准', grayed: false, tone: 'sage' },
  rejected: { label: '已拒绝', grayed: true, tone: 'ink' },
  expired: { label: '已过期', grayed: true, tone: 'ink' },
  cancelled: { label: '已取消', grayed: true, tone: 'ink' },
}

/**
 * EvidenceSource 的中文展示名。读取证据条目旁的小标签。
 */
export const EVIDENCE_SOURCE_LABELS: Record<EvidenceSource, string> = {
  literal_search: '字面检索',
  relevance_search: '相关度检索',
  direct_read: '直接读取',
}
