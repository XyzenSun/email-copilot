<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import AppHeader from '@/components/layout/AppHeader.vue'
import ThreeColumn from '@/components/layout/ThreeColumn.vue'
import Banner from '@/components/ui/Banner.vue'
import Toast from '@/components/ui/Toast.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ThreadView from '@/components/mail/ThreadView.vue'

/**
 * 根布局。
 *
 * 登录页（/login）只渲染登录页，不显示三栏 shell（用路由名判断）。
 * 其余路由：默认密码横幅（常驻不可关）+ 顶栏 + 三栏 shell + Toast 容器。
 *
 * router-view 放置策略：
 *   - 邮件列表路由（inbox/sent/search）：router-view 放中栏（列表，384px），
 *     右栏留给会话详情（ThreadView，?thread= 驱动；无 ?thread= 时显示空态）。
 *   - 非邮件列表路由（drafts/conversations/actions/settings 等）：router-view 放右栏整屏。
 *   两个 router-view 用 v-if 互斥，同一时刻只有一个活跃，不会重复渲染。
 *
 * drafts 不在 MAIL_ROUTE_NAMES 里：草稿页是编辑型页面需要全宽（列表+编辑器两栏），
 * 不适合塞进 384px 中栏。inbox/sent/search 是列表型，中栏列表 + 右栏详情正好。
 */
const auth = useAuthStore()
const ui = useUiStore()
const route = useRoute()

/** 邮件列表路由：中栏列表 + 右栏会话详情（?thread= 驱动） */
const MAIL_ROUTE_NAMES = new Set(['inbox', 'sent', 'search'])

const isLoginRoute = computed(() => route.name === 'login')
const isMailRoute = computed(() => {
  const name = route.name
  return typeof name === 'string' && MAIL_ROUTE_NAMES.has(name)
})
const hasThread = computed(() => route.query.thread !== undefined)

/** 从 route.query 解析 threadId（数字，无效时 null） */
const threadId = computed(() => {
  const t = route.query.thread
  if (typeof t === 'string') {
    const n = Number(t)
    if (!isNaN(n) && n > 0) return n
  }
  return null
})

/** 从 route.query 解析 msgId（高亮定位，可选） */
const msgId = computed(() => {
  const m = route.query.msg
  if (typeof m === 'string') {
    const n = Number(m)
    if (!isNaN(n) && n > 0) return n
  }
  return undefined
})
</script>

<template>
  <!-- 登录页不显示三栏 shell -->
  <router-view v-if="isLoginRoute" />

  <div v-else class="h-full flex flex-col">
    <!-- 默认密码常驻横幅（FRONTEND.md §3.1：不可一次性关闭，链到改密） -->
    <Banner v-if="auth.usingDefaultPassword" variant="warn" icon="fa-solid fa-wand-magic-sparkles">
      <span
        >安全提示：系统当前使用默认登录密码（admin / admin123456），建议前往账户设置修改。</span
      >
      <template #action>
        <router-link
          to="/settings/account"
          class="underline hover:text-ink-800 font-medium whitespace-nowrap"
          >去修改密码 →</router-link
        >
      </template>
    </Banner>

    <AppHeader />

    <ThreeColumn>
      <!-- 中栏：邮件类路由的列表（router-view 渲染 MailListPage 等） -->
      <router-view v-if="isMailRoute" />
      <!-- 右栏：非邮件列表路由整屏；邮件列表路由按 ?thread= 切换空态/会话详情 -->
      <template #main>
        <router-view v-if="!isMailRoute" />
        <ThreadView
          v-else-if="hasThread && threadId != null"
          :thread-id="threadId"
          :msg-id="msgId"
        />
        <EmptyState
          v-else
          icon="fa-regular fa-envelope-open"
          title="选一封邮件查看详情"
          description="点击左侧列表中的邮件，会在此展开完整内容"
        />
      </template>
    </ThreeColumn>

    <!-- Toast 容器（右上角，pointer-events-none 容器 + auto 子项让间隙可点穿） -->
    <div class="fixed top-4 right-4 z-[200] flex flex-col gap-2 pointer-events-none">
      <Toast
        v-for="t in ui.toasts"
        :key="t.id"
        :toast="t"
        class="pointer-events-auto"
        @close="ui.dismissToast(t.id)"
      />
    </div>
  </div>
</template>
