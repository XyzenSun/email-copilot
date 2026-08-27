/**
 * authStore —— 登录态、CSRF ready、默认密码标记。
 * FRONTEND.md §5：只存跨页面 UI 状态，不存业务数据。
 *
 * 关键：
 *   - usingDefaultPassword 为 true 时全局显示常驻横幅（不可一次性关闭）。
 *   - 启动时 GET /session 探态，这步同时让后端下发 XSRF-TOKEN cookie（csrfReady）。
 *   - 会话存内存，后端重启后任何接口返回 401 AUTHENTICATION_REQUIRED → 跳登录页。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'

export const useAuthStore = defineStore('auth', () => {
  /** 是否已登录 */
  const authenticated = ref(false)
  /** 当前用户名（未登录为 null） */
  const username = ref<string | null>(null)
  /** 是否仍在用默认密码 admin/admin123456 */
  const usingDefaultPassword = ref(false)
  /** CSRF token cookie 是否已就绪（GET /session 后为 true） */
  const csrfReady = ref(false)
  /** 会话失效提示（401 跳登录页时置，登录页展示一次后清除） */
  const sessionExpiredMessage = ref<string | null>(null)

  /**
   * 探测登录态。应用启动时调一次。
   * 即使未登录也返回 200（API.md §7.1），authenticated 字段判定。
   * 这步同时下发 XSRF-TOKEN cookie。
   */
  async function fetchSession(): Promise<void> {
    const { data, error } = await api.GET('/session')
    if (error) {
      // GET /session 不应返回 4xx（未登录也是 200）；走到这是异常，保守置未登录
      authenticated.value = false
      csrfReady.value = false
      return
    }
    authenticated.value = data.authenticated
    username.value = data.username
    usingDefaultPassword.value = data.usingDefaultPassword
    // 后端在响应时已通过 CsrfCookieFilter 下发 XSRF-TOKEN cookie（若 token 已生成）
    csrfReady.value = true
  }

  /**
   * 登录。失败抛 ProblemError（INVALID_CREDENTIALS / LOGIN_ATTEMPTS_EXCEEDED），
   * 由 Login 页按 code 分支处理。
   */
  async function login(loginUsername: string, password: string): Promise<void> {
    const { data, error } = await api.POST('/session', {
      body: { username: loginUsername, password },
    })
    if (error) {
      throw error
    }
    authenticated.value = data.authenticated
    username.value = data.username
    usingDefaultPassword.value = data.usingDefaultPassword
    // 登录成功后后端会下发新 CSRF token cookie
    csrfReady.value = true
    sessionExpiredMessage.value = null
  }

  /** 登出。 */
  async function logout(): Promise<void> {
    await api.DELETE('/session')
    reset()
  }

  /** 清空本地登录态（401 失效或登出后）。 */
  function reset(): void {
    authenticated.value = false
    username.value = null
    usingDefaultPassword.value = false
    csrfReady.value = false
  }

  /** 标记会话失效（unauthMiddleware 跳登录前调）。 */
  function markSessionExpired(message = '会话已失效，请重新登录'): void {
    reset()
    sessionExpiredMessage.value = message
  }

  return {
    authenticated,
    username,
    usingDefaultPassword,
    csrfReady,
    sessionExpiredMessage,
    fetchSession,
    login,
    logout,
    reset,
    markSessionExpired,
  }
})
