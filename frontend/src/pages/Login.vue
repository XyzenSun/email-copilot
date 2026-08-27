<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { isProblem, onCode } from '@/api/errors'
import Spin from '@/components/ui/Spin.vue'

/**
 * 登录页。FRONTEND.md §3.1。
 *
 * - 用户名 + 密码提交 authStore.login()，成功后跳 redirect（仅站内路径，防开放重定向）。
 * - 失败按 code 分支（据 code 不据 title）：
 *     INVALID_CREDENTIALS → 「账号或密码错误」（不区分用户名/密码，防枚举）
 *     LOGIN_ATTEMPTS_EXCEEDED → 显示 detail 里的剩余锁定时间
 *     其余 → 通用错误
 * - authStore.sessionExpiredMessage 有值时顶部显示会话失效提示（401 跳来时置，登录成功后清）。
 */
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const username = ref('')
const password = ref('')
const loading = ref(false)
const errorMessage = ref<string | null>(null)

/** 登录后跳转目标。仅允许站内路径（/ 开头），防开放重定向。 */
const redirect = computed(() => {
  const r = route.query.redirect
  if (typeof r === 'string' && r.startsWith('/')) return r
  return '/'
})

async function handleSubmit(): Promise<void> {
  if (!username.value || !password.value || loading.value) return
  loading.value = true
  errorMessage.value = null
  try {
    await auth.login(username.value, password.value)
    // login 成功已清 sessionExpiredMessage；跳转目标页
    await router.push(redirect.value)
  } catch (err) {
    if (isProblem(err)) {
      const problem = err
      // 据 code 分支（不据 title——那是可改文案）
      errorMessage.value =
        onCode(
          problem,
          {
            // 不区分用户名/密码哪个错，避免账号枚举（security-guidelines）
            INVALID_CREDENTIALS: () => '账号或密码错误',
            // detail 里有剩余锁定时间
            LOGIN_ATTEMPTS_EXCEEDED: () =>
              problem.detail ?? '登录尝试次数过多，请稍后再试',
          },
          () => '登录失败，请稍后再试',
        ) ?? '登录失败，请稍后再试'
    } else {
      errorMessage.value = '登录失败，请稍后再试'
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="h-full flex items-center justify-center bg-warm-white px-4">
    <div class="w-full max-w-sm">
      <!-- 品牌 -->
      <div class="flex flex-col items-center mb-8">
        <div
          class="w-12 h-12 rounded-2xl bg-dark-stone text-warm-white flex items-center justify-center shadow-sm mb-3"
        >
          <i class="fa-solid fa-envelope text-lg" />
        </div>
        <h1 class="font-serif font-semibold text-xl text-deep-charcoal">email-copilot</h1>
        <p class="text-xs text-ink-400 mt-1">单用户多邮箱聚合 AI 邮件助手</p>
      </div>

      <!-- 会话失效提示（401 跳转来时置，登录成功后由 authStore.login 清除） -->
      <div
        v-if="auth.sessionExpiredMessage"
        class="mb-4 px-4 py-2.5 rounded-xl bg-warn-bg border border-warn-border text-warn-text text-xs flex items-center gap-2"
      >
        <i class="fa-solid fa-triangle-exclamation shrink-0" />
        <span>{{ auth.sessionExpiredMessage }}</span>
      </div>

      <!-- 登录表单 -->
      <form
        class="bg-white rounded-2xl border border-border shadow-sm hygge-card p-6 space-y-4"
        @submit.prevent="handleSubmit"
      >
        <div class="space-y-1.5">
          <label for="username" class="block text-xs font-medium text-stone-grey">用户名</label>
          <input
            id="username"
            v-model="username"
            type="text"
            class="w-full hygge-input rounded-xl px-3.5 py-2.5 text-sm"
            placeholder="请输入用户名"
            autocomplete="username"
            :disabled="loading"
          />
        </div>

        <div class="space-y-1.5">
          <label for="password" class="block text-xs font-medium text-stone-grey">密码</label>
          <input
            id="password"
            v-model="password"
            type="password"
            class="w-full hygge-input rounded-xl px-3.5 py-2.5 text-sm"
            placeholder="请输入密码"
            autocomplete="current-password"
            :disabled="loading"
          />
        </div>

        <!-- 错误提示（据 code 分支渲染） -->
        <p v-if="errorMessage" class="text-xs text-danger-text flex items-center gap-1.5">
          <i class="fa-solid fa-circle-exclamation shrink-0" />
          {{ errorMessage }}
        </p>

        <button
          type="submit"
          class="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl bg-dark-stone text-warm-white text-sm font-medium hover:bg-deep-charcoal transition disabled:opacity-50 disabled:cursor-not-allowed"
          :disabled="loading || !username || !password"
        >
          <Spin v-if="loading" size="text-sm" />
          <span>{{ loading ? '登录中…' : '登录' }}</span>
        </button>
      </form>

      <p class="text-center text-[10px] text-ink-300 mt-6 font-mono">默认账号 admin / admin123456</p>
    </div>
  </div>
</template>
