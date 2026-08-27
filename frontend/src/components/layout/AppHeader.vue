<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'

/**
 * 顶栏。汉堡菜单（窄屏开侧栏抽屉）、品牌名、搜索框（从侧栏移来）、用户菜单（登出）。
 * 照原型：h-16、毛玻璃面板、左侧品牌、中间搜索、右侧用户信息。
 */
const auth = useAuthStore()
const ui = useUiStore()
const router = useRouter()

/** 顶栏搜索关键词（回车跳 /search?q=） */
const searchQuery = ref('')

/** 用户名首两字符作头像缩写 */
const initials = computed(() => {
  const name = auth.username
  if (!name) return '?'
  return name.slice(0, 2).toUpperCase()
})

async function logout(): Promise<void> {
  await auth.logout()
  await router.push({ name: 'login' })
}

/** 回车提交搜索：跳 /search 带关键词。空则也进搜索页（让用户在那填筛选条件）。 */
function submitSearch(): void {
  const q = searchQuery.value.trim()
  void router.push({ name: 'search', query: q ? { q } : {} })
  searchQuery.value = ''
}
</script>

<template>
  <header
    class="h-16 border-b border-border bg-warm-white/90 px-4 sm:px-6 flex items-center justify-between gap-3 shrink-0 hygge-panel"
  >
    <div class="flex items-center gap-3 shrink-0">
      <!-- 汉堡：仅窄屏，点开左侧导航抽屉 -->
      <button
        type="button"
        class="lg:hidden p-2 -ml-1 rounded-xl text-stone-grey hover:bg-light-beige transition"
        @click="ui.toggleSidebar()"
        aria-label="打开导航"
      >
        <i class="fa-solid fa-bars text-base" />
      </button>
      <div
        class="w-9 h-9 rounded-xl bg-dark-stone text-warm-white flex items-center justify-center shadow-sm shrink-0"
      >
        <i class="fa-solid fa-envelope text-sm" />
      </div>
      <span class="font-serif font-semibold text-lg tracking-tight text-deep-charcoal"
        >email-copilot</span
      >
    </div>

    <!-- 顶栏搜索框（从侧栏移来）：桌面端显示，回车跳 /search -->
    <form class="hidden sm:flex flex-1 max-w-md mx-auto" @submit.prevent="submitSearch">
      <div class="relative w-full">
        <i
          class="fa-solid fa-magnifying-glass absolute left-3.5 top-1/2 -translate-y-1/2 text-xs text-ink-300"
        />
        <input
          v-model="searchQuery"
          type="search"
          class="w-full hygge-input rounded-xl pl-9 pr-3 py-2 text-xs"
          placeholder="搜索邮件（关键词、发件人、主题）"
          aria-label="搜索邮件"
        />
      </div>
    </form>

    <div class="flex items-center gap-3 sm:gap-5 shrink-0">
      <!-- 用户菜单 -->
      <div class="flex items-center gap-2.5">
        <div
          class="w-8 h-8 rounded-full bg-light-beige border border-border flex items-center justify-center text-xs text-stone-grey font-serif font-semibold"
        >
          {{ initials }}
        </div>
        <span class="text-xs text-stone-grey font-medium hidden sm:inline">{{
          auth.username ?? '未登录'
        }}</span>
        <button
          type="button"
          class="text-xs text-ink-300 hover:text-stone-grey ml-1 transition"
          @click="logout"
          aria-label="登出"
          title="登出"
        >
          <i class="fa-solid fa-arrow-right-from-bracket" />
        </button>
      </div>
    </div>
  </header>
</template>
