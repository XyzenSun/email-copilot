<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppSidebar from './AppSidebar.vue'

/**
 * 三栏布局壳。左 AppSidebar + 中栏 slot（邮件列表）+ 右栏 slot（详情/整屏）。
 *
 * 响应式互斥（FRONTEND.md §2）：
 *   - 邮件列表路由（inbox/sent/search）：中栏列表 + 右栏详情。
 *     窄屏下二选一——有 ?thread= 显示详情隐藏列表，无 ?thread= 显示列表隐藏详情。
 *     桌面端（lg）两栏并排。
 *   - 非邮件列表路由（drafts/conversations/actions/settings 等）：不渲染中栏，右栏整屏。
 *
 * 「返回列表」按钮：窄屏专用（lg:hidden），邮件列表路由有 ?thread= 时显示在右栏头，
 * 点击清掉 ?thread= 与 ?msg= 回到列表（保留其余 query 如搜索词）。
 */
const route = useRoute()
const router = useRouter()

const MAIL_ROUTE_NAMES = new Set(['inbox', 'sent', 'search'])

const isMailRoute = computed(() => {
  const name = route.name
  return typeof name === 'string' && MAIL_ROUTE_NAMES.has(name)
})

const hasThread = computed(() => route.query.thread !== undefined)

/** 窄屏返回列表：清掉 ?thread= 与 ?msg=，保留其余 query */
function backToList(): void {
  const rest: Record<string, string> = {}
  for (const [key, value] of Object.entries(route.query)) {
    if (key !== 'thread' && key !== 'msg' && typeof value === 'string') {
      rest[key] = value
    }
  }
  void router.replace({ query: rest })
}
</script>

<template>
  <div class="flex-1 flex overflow-hidden">
    <AppSidebar />

    <!-- 中栏：邮件列表（仅邮件类路由） -->
    <section
      v-if="isMailRoute"
      :class="[
        'w-full lg:w-[384px] border-r border-border bg-warm-white flex-col shrink-0 overflow-hidden',
        hasThread ? 'hidden lg:flex' : 'flex',
      ]"
    >
      <slot />
    </section>

    <!-- 右栏：详情 / 整屏页面 -->
    <section
      :class="[
        'flex-1 flex-col overflow-hidden',
        isMailRoute && !hasThread ? 'hidden lg:flex' : 'flex',
      ]"
    >
      <!-- 窄屏返回列表按钮 -->
      <div
        v-if="isMailRoute && hasThread"
        class="lg:hidden flex items-center px-4 py-2 border-b border-border bg-light-beige shrink-0"
      >
        <button
          type="button"
          class="flex items-center gap-2 text-xs text-stone-grey hover:text-deep-charcoal transition"
          @click="backToList"
        >
          <i class="fa-solid fa-arrow-left" /> 返回列表
        </button>
      </div>
      <slot name="main" />
    </section>
  </div>
</template>
