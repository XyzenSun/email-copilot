<script setup lang="ts">
import { useRoute } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import LiveLogPanel from './LiveLogPanel.vue'

/**
 * 左侧主导航（FRONTEND.md §4，经 Xyzen 2026-08-24 调整分组与命名）。
 *
 * 分组：
 *   - 收件箱（单独）
 *   - 发信：写邮箱/草稿箱（drafts）+ 发件箱（sent，原「已发送」）
 *   - copilot：copilot（conversations，原「对话」）+ 提案审批（actions，带角标）
 *   - 设置
 * 搜索已移到顶栏 AppHeader，不在此处。
 *
 * 窄屏下是抽屉（uiStore.sidebarOpen 控制），遮罩点击关闭。
 * 桌面端常驻（lg:static lg:translate-x-0）。
 */
const ui = useUiStore()
const route = useRoute()

interface NavItem {
  name: string
  label: string
  icon: string
  to: string
  /** 是否显示未决角标 */
  badge?: boolean
}

interface NavGroup {
  title: string
  items: NavItem[]
}

const navGroups: NavGroup[] = [
  {
    title: '收件箱',
    items: [{ name: 'inbox', label: '收件箱', icon: 'fa-regular fa-envelope', to: '/' }],
  },
  {
    title: '发信',
    items: [
      { name: 'drafts', label: '写邮箱/草稿箱', icon: 'fa-regular fa-pen-to-square', to: '/drafts' },
      { name: 'sent', label: '发件箱', icon: 'fa-regular fa-paper-plane', to: '/sent' },
    ],
  },
  {
    title: 'copilot',
    items: [
      { name: 'conversations', label: 'copilot', icon: 'fa-regular fa-comments', to: '/conversations' },
      {
        name: 'actions',
        label: '提案审批',
        icon: 'fa-regular fa-circle-check',
        to: '/actions',
        badge: true,
      },
    ],
  },
  {
    title: '系统',
    items: [{ name: 'settings', label: '设置', icon: 'fa-solid fa-gear', to: '/settings' }],
  },
]

/**
 * 高亮当前路由。settings 有子路由（/settings/account 等），用前缀匹配；
 * inbox 是根路径 '/'，必须精确匹配（否则所有路径都命中）。
 */
function isActive(item: NavItem): boolean {
  if (item.name === 'settings') return route.path.startsWith('/settings')
  if (item.name === 'inbox') return route.path === '/'
  return route.path === item.to
}

/** 窄屏下点导航项后关闭抽屉 */
function onNavigate(): void {
  ui.closeSidebar()
}
</script>

<template>
  <!-- 窄屏遮罩：点遮罩关闭抽屉 -->
  <div
    v-if="ui.sidebarOpen"
    class="lg:hidden fixed inset-0 bg-deep-charcoal/25 z-[55]"
    @click="ui.closeSidebar()"
  />

  <aside
    :class="[
      'w-60 border-r border-border bg-light-beige p-3.5 flex flex-col shrink-0 overflow-y-auto z-[60]',
      'fixed inset-y-0 left-0 shadow-2xl transition-transform duration-200 lg:static lg:shadow-none lg:translate-x-0 lg:z-auto',
      ui.sidebarOpen ? 'translate-x-0' : '-translate-x-full lg:translate-x-0',
    ]"
  >
    <!-- 抽屉关闭按钮：窄屏下它盖住顶栏，没有这个就只能点遮罩 -->
    <button
      type="button"
      class="lg:hidden w-full flex items-center gap-2 px-3 py-2 mb-1 rounded-xl text-xs text-stone-grey hover:bg-sand/60 transition"
      @click="ui.closeSidebar()"
    >
      <i class="fa-solid fa-xmark" /> 关闭
    </button>

    <nav class="flex-1 space-y-4">
      <div v-for="group in navGroups" :key="group.title">
        <div
          class="text-[10px] font-mono uppercase tracking-wider text-ink-300 px-3 py-1 font-semibold"
        >
          {{ group.title }}
        </div>
        <div class="space-y-1">
          <router-link
            v-for="item in group.items"
            :key="item.name"
            :to="item.to"
            class="w-full flex items-center justify-between px-3.5 py-2.5 rounded-xl text-xs font-medium transition"
            :class="
              isActive(item)
                ? 'bg-white text-deep-charcoal shadow-sm border border-border'
                : 'text-stone-grey hover:bg-sand/50 hover:text-deep-charcoal'
            "
            @click="onNavigate"
          >
            <div class="flex items-center gap-3">
              <i :class="item.icon" class="text-sm" />
              <span>{{ item.label }}</span>
            </div>
            <span
              v-if="item.badge && ui.pendingActionBadge > 0"
              class="px-2 py-0.5 rounded-full text-[10px] font-mono bg-clay text-white font-bold"
            >
              {{ ui.pendingActionBadge }}
            </span>
          </router-link>
        </div>
      </div>
    </nav>

    <!-- 实时日志（后端 SSE 接口未就绪，UI 占位） -->
    <LiveLogPanel />

    <!-- 系统状态占位（后续批接入真实状态） -->
    <div class="mt-3 p-3 rounded-2xl bg-white border border-border space-y-2 shadow-sm shrink-0">
      <div class="flex items-center justify-between">
        <span class="font-serif text-[11px] font-semibold text-deep-charcoal">系统状态</span>
        <span class="text-[9px] font-mono flex items-center gap-1 text-sage">
          <i class="fa-solid fa-circle text-[5px]" /> 正常
        </span>
      </div>
    </div>
  </aside>
</template>
