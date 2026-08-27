/**
 * 邮件相关的共享常量与纯函数。
 *
 * 集中放分类标签映射、收件人格式化、文件大小格式化、地址解析——
 * 多个组件复用（MailListItem / MessageBubble / ThreadView / Drafts），
 * 避免散落重复。code-reuse-thinking-guide：同一逻辑出现 2+ 处即提取。
 */
import type { components } from '@/api/types.gen'

export type MessageSummary = components['schemas']['MessageSummary']
export type MessageDetail = components['schemas']['MessageDetail']
export type Tag = components['schemas']['Tag']
export type MailAccount = components['schemas']['MailAccount']
export type Category = components['schemas']['Category']
export type Recipients = components['schemas']['Recipients']
export type Draft = components['schemas']['Draft']
export type SendResult = components['schemas']['SendResult']
export type ReprocessStage = components['schemas']['ReprocessStage']

/**
 * 六分类的中文展示名。与 openapi Category 枚举一一对应，
 * Chip 组件的 CAT_STYLE 配色也用同样的 key。
 */
export const CATEGORY_LABELS: Record<Category, string> = {
  primary: '主要',
  transaction: '交易',
  promotion: '推广',
  social: '社交',
  update: '更新',
  spam: '垃圾',
}

/** 分类 tab 配置：value 为 null 表示「全部」（不按分类过滤）。 */
export const CATEGORY_TABS: ReadonlyArray<{ value: Category | null; label: string }> = [
  { value: null, label: '全部' },
  { value: 'primary', label: '主要' },
  { value: 'transaction', label: '交易' },
  { value: 'promotion', label: '推广' },
  { value: 'social', label: '社交' },
  { value: 'update', label: '更新' },
  { value: 'spam', label: '垃圾' },
]

/**
 * 格式化收件人为展示字符串。优先 to，其次 cc，合并展示。
 * outbound 邮件在列表里显示收件人而非发件人（发件人恒为自己）。
 */
export function formatRecipients(recipients: Recipients): string {
  const all = [...recipients.to, ...recipients.cc]
  if (all.length === 0) return '(无收件人)'
  return all.join(', ')
}

/**
 * 格式化文件大小。附件只存元数据无下载，展示文件名+大小供用户判断
 * 是否回原邮箱查看（附件是攻击载荷最集中的位置）。
 */
export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/**
 * 把逗号分隔的地址文本解析为地址数组。
 * 回复编辑器 / 草稿编辑器用单个文本框输入收件人（逗号分隔），发送前解析。
 */
export function parseAddresses(text: string): string[] {
  return text
    .split(/[;,]/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
}

/** 把地址数组拼成展示文本（逗号+空格分隔）。 */
export function joinAddresses(addresses: string[]): string {
  return addresses.join(', ')
}
