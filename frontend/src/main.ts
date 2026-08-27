/**
 * 应用入口。串联 Pinia + Router，注入 401 会话失效处理器，启动时探登录态。
 * design.md §3.2、§4.2。
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'
import { useUiStore } from './stores/ui'
import { configureSessionExpiredHandler } from './api/client'
import './styles/main.css'
// FontAwesome 全量样式（图标用 <i class="fa-solid fa-xxx">）
import '@fortawesome/fontawesome-free/css/all.min.css'

async function bootstrap(): Promise<void> {
  const app = createApp(App)
  const pinia = createPinia()
  app.use(pinia)

  // 401 会话失效处理器：必须先装 pinia 才能拿 store。
  // unauthMiddleware 检测到 AUTHENTICATION_REQUIRED 时回调 → 清态 + 跳登录 + 提示。
  const auth = useAuthStore()
  const ui = useUiStore()
  configureSessionExpiredHandler(() => {
    auth.markSessionExpired()
    // 避免在已是登录页时重复 push
    if (router.currentRoute.value.name !== 'login') {
      void router.push({ name: 'login' })
    }
  })

  // 启动探登录态。GET /session 同时下发 XSRF-TOKEN cookie。
  // 失败也继续挂载（守卫会把未登录用户导向 /login）。
  await auth.fetchSession()
  // 已登录则拉一次提案审批角标
  if (auth.authenticated) {
    void ui.refreshPendingBadge()
  }

  app.use(router)
  app.mount('#app')
}

void bootstrap()
