/**
 * uiStore —— 跨页面 UI 状态。FRONTEND.md §5。
 *
 * 不存业务数据（邮件/会话/Turn 不进这里）。只存：
 *   - 侧栏开关（窄屏抽屉）
 *   - 当前会话 id（右栏渲染 ThreadView 依据，配合路由 ?thread=）
 *   - 提案审批角标数（侧栏 /actions 旁的数字）
 *   - 当前邮箱账号 id（列表页账号切换）
 *   - 全局 toast 队列（操作反馈）
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'

export interface ToastItem {
  id: number
  type: 'success' | 'error' | 'info'
  message: string
}

let toastSeq = 1

export const useUiStore = defineStore('ui', () => {
  /** 窄屏侧栏抽屉是否展开 */
  const sidebarOpen = ref(false)
  /** 当前右栏展示的会话 id（与路由 ?thread= 同步） */
  const currentThreadId = ref<number | null>(null)
  /** 提案审批 pending 数量（侧栏角标） */
  const pendingActionBadge = ref(0)
  /** 当前选中的邮箱账号 id（列表过滤）；null=全部账号 */
  const currentMailAccountId = ref<number | null>(null)
  /** 全局 toast 队列 */
  const toasts = ref<ToastItem[]>([])

  function toggleSidebar(): void {
    sidebarOpen.value = !sidebarOpen.value
  }

  function closeSidebar(): void {
    sidebarOpen.value = false
  }

  function pushToast(type: ToastItem['type'], message: string, durationMs = 3000): void {
    const id = toastSeq++
    toasts.value.push({ id, type, message })
    // 自动消失。setTimeout 在前端是标准做法（单用户自用，无 SSR）
    window.setTimeout(() => dismissToast(id), durationMs)
  }

  function dismissToast(id: number): void {
    toasts.value = toasts.value.filter((t) => t.id !== id)
  }

  /**
   * 拉取提案审批角标数。登录后调一次；审批/拒绝后刷新。
   * 不轮询、不另开计数端点——复用 GET /actions?status=pending&size=1 的 total（FRONTEND.md §3.6）。
   */
  async function refreshPendingBadge(): Promise<void> {
    const { data, error } = await api.GET('/actions', {
      params: { query: { status: 'pending', size: 1 } },
    })
    if (!error && data) {
      pendingActionBadge.value = data.total
    }
  }

  return {
    sidebarOpen,
    currentThreadId,
    pendingActionBadge,
    currentMailAccountId,
    toasts,
    toggleSidebar,
    closeSidebar,
    pushToast,
    dismissToast,
    refreshPendingBadge,
  }
})
