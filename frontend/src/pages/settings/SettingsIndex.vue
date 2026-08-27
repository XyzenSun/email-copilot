<script setup lang="ts">
import { useRoute } from 'vue-router'

/**
 * 设置首页布局。左侧子导航 + 右侧 router-view（子路由渲染）。
 * 批3 实现各子页真实内容，当前子页为占位。
 */
const route = useRoute()

interface SettingsNavItem {
  name: string
  label: string
  icon: string
  to: string
}

const navItems: SettingsNavItem[] = [
  { name: 'settings-account', label: '账户安全', icon: 'fa-solid fa-lock', to: '/settings/account' },
  { name: 'settings-email-accounts', label: '邮箱账号', icon: 'fa-solid fa-at', to: '/settings/email-accounts' },
  { name: 'settings-sender-rules', label: '发件人规则', icon: 'fa-solid fa-shield', to: '/settings/sender-rules' },
  { name: 'settings-tags', label: '自定义标签设置', icon: 'fa-solid fa-tag', to: '/settings/tags' },
  { name: 'settings-guardrails', label: '系统参数配置', icon: 'fa-solid fa-sliders', to: '/settings/guardrails' },
  { name: 'settings-system', label: 'AI 连接', icon: 'fa-solid fa-microchip', to: '/settings/system' },
  { name: 'settings-pipeline', label: 'AI 开关与垃圾评分', icon: 'fa-solid fa-gauge', to: '/settings/pipeline' },
]

function isActive(item: SettingsNavItem): boolean {
  return route.name === item.name
}
</script>

<template>
  <div class="flex h-full overflow-hidden">
    <!-- 左侧子导航 -->
    <aside class="w-56 border-r border-border bg-light-beige p-3 flex flex-col shrink-0 overflow-y-auto">
      <div class="text-[10px] font-mono uppercase tracking-wider text-ink-300 px-3 py-1 font-semibold">
        设置
      </div>
      <nav class="space-y-1">
        <router-link
          v-for="item in navItems"
          :key="item.name"
          :to="item.to"
          class="w-full flex items-center gap-2.5 px-3 py-2 rounded-xl text-xs font-medium transition"
          :class="
            isActive(item)
              ? 'bg-white text-deep-charcoal shadow-sm border border-border'
              : 'text-stone-grey hover:bg-sand/50 hover:text-deep-charcoal'
          "
        >
          <i :class="item.icon" class="text-xs" />
          <span>{{ item.label }}</span>
        </router-link>
      </nav>
    </aside>

    <!-- 右侧子页内容 -->
    <div class="flex-1 overflow-y-auto">
      <router-view />
    </div>
  </div>
</template>
