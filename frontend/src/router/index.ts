/**
 * 路由表 + 鉴权守卫。FRONTEND.md §2、design.md §4。
 *
 * 10 条路由，会话详情不做 /thread/:id 独立路由——用 ?thread= query 挂在列表路由上
 * （DECISIONS §9 已确认）。路由懒加载（动态 import）做代码分割。
 *
 * 鉴权守卫：非 /login 路由未登录 → 跳 /login?redirect=原路径。
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/pages/Login.vue'),
    meta: { auth: false },
  },
  {
    path: '/',
    name: 'inbox',
    component: () => import('@/pages/MailListPage.vue'),
    meta: { auth: true, direction: 'inbound' },
  },
  {
    path: '/sent',
    name: 'sent',
    component: () => import('@/pages/MailListPage.vue'),
    meta: { auth: true, direction: 'outbound' },
  },
  {
    path: '/drafts',
    name: 'drafts',
    component: () => import('@/pages/Drafts.vue'),
    meta: { auth: true },
  },
  {
    path: '/search',
    name: 'search',
    component: () => import('@/pages/Search.vue'),
    meta: { auth: true },
  },
  {
    path: '/conversations',
    name: 'conversations',
    component: () => import('@/pages/ConversationsList.vue'),
    meta: { auth: true },
  },
  {
    path: '/conversations/:id',
    name: 'conversation',
    component: () => import('@/pages/ConversationDetail.vue'),
    meta: { auth: true },
    props: true,
  },
  {
    path: '/actions',
    name: 'actions',
    component: () => import('@/pages/Actions.vue'),
    meta: { auth: true },
  },
  {
    path: '/settings',
    component: () => import('@/pages/settings/SettingsIndex.vue'),
    meta: { auth: true },
    children: [
      { path: '', redirect: '/settings/account' },
      {
        path: 'account',
        name: 'settings-account',
        component: () => import('@/pages/settings/Account.vue'),
        meta: { auth: true },
      },
      {
        path: 'email-accounts',
        name: 'settings-email-accounts',
        component: () => import('@/pages/settings/EmailAccounts.vue'),
        meta: { auth: true },
      },
      {
        path: 'sender-rules',
        name: 'settings-sender-rules',
        component: () => import('@/pages/settings/SenderRules.vue'),
        meta: { auth: true },
      },
      {
        path: 'tags',
        name: 'settings-tags',
        component: () => import('@/pages/settings/Tags.vue'),
        meta: { auth: true },
      },
      {
        path: 'guardrails',
        name: 'settings-guardrails',
        component: () => import('@/pages/settings/Guardrails.vue'),
        meta: { auth: true },
      },
      {
        path: 'system',
        name: 'settings-system',
        component: () => import('@/pages/settings/System.vue'),
        meta: { auth: true },
      },
      {
        path: 'pipeline',
        name: 'settings-pipeline',
        component: () => import('@/pages/settings/Pipeline.vue'),
        meta: { auth: true },
      },
    ],
  },
  // 兜底：未知路径回收件箱
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  // 切路由时滚到顶（除会话详情保留滚动）
  scrollBehavior(_to, _from, savedPosition) {
    return savedPosition ?? { top: 0 }
  },
})

/**
 * 鉴权守卫。非 /login 路由未登录 → 跳登录页并记 redirect。
 * authStore 在 main.ts 启动时已 fetchSession 探过态。
 */
router.beforeEach((to) => {
  const auth = useAuthStore()
  const requiresAuth = to.meta.auth !== false

  if (requiresAuth && !auth.authenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  // 已登录还想去登录页 → 直接进收件箱
  if (to.name === 'login' && auth.authenticated) {
    return { name: 'inbox' }
  }
  return true
})

export default router
