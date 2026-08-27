<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { api } from '@/api/client'
import { isProblem, onCode, fieldErrors } from '@/api/errors'
import Spin from '@/components/ui/Spin.vue'
import Banner from '@/components/ui/Banner.vue'

/**
 * 账户安全页（FRONTEND.md §3.7）。
 *
 * 改登录密码：PATCH /owner/password → 204（无 content）。成功后后端作废全部 session
 * （含当前），前端清本地登录态并跳登录页——改密的动机往往正是怀疑密码泄露，
 * 保留当前 session 等于给可能已在里面的人留门。
 *
 * 仍用默认密码时此页高亮为待办（authStore.usingDefaultPassword）。
 * 新密码强度前端只做基础校验（非空、≥8 位、两次一致），后端 Bean Validation 才是权威。
 */
const auth = useAuthStore()
const ui = useUiStore()
const router = useRouter()

const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const loading = ref(false)
/** 字段级错误映射（field → message），由前端校验或后端 VALIDATION_FAILED 填充 */
const fieldErr = ref<Record<string, string>>({})

const usingDefault = computed(() => auth.usingDefaultPassword)

/** 前端基础校验。后端才是权威，这里只挡明显的空值与长度问题。 */
function validate(): boolean {
  fieldErr.value = {}
  if (!currentPassword.value) {
    fieldErr.value.currentPassword = '请输入当前密码'
  }
  if (!newPassword.value) {
    fieldErr.value.newPassword = '请输入新密码'
  } else if (newPassword.value.length < 8) {
    // 与后端 @Size(min=8) 对齐——两边不一致时前端会放过一个后端要拒的值
    fieldErr.value.newPassword = '新密码至少 8 个字符'
  }
  if (newPassword.value && confirmPassword.value !== newPassword.value) {
    fieldErr.value.confirmPassword = '两次输入的新密码不一致'
  }
  return Object.keys(fieldErr.value).length === 0
}

async function handleSubmit(): Promise<void> {
  if (loading.value) return
  if (!validate()) return

  loading.value = true
  try {
    // 204 无 content；4xx/5xx 由 problemMiddleware 抛 ProblemError
    await api.PATCH('/owner/password', {
      body: {
        currentPassword: currentPassword.value,
        newPassword: newPassword.value,
      },
    })
    // 成功：后端已作废全部 session，清本地态并跳登录
    ui.pushToast('success', '密码已修改，请重新登录')
    auth.reset()
    await router.push({ name: 'login' })
  } catch (err) {
    if (isProblem(err)) {
      const problem = err
      // 据 code 分支，不据 title（那是可改文案）
      onCode(problem, {
        // 当前密码不对：401 INVALID_CREDENTIALS
        // （非 AUTHENTICATION_REQUIRED，unauthMiddleware 不触发全局登出）
        INVALID_CREDENTIALS: () => {
          fieldErr.value.currentPassword = '当前密码不正确'
        },
        // 后端字段级校验是权威（如新密码 < 8）
        VALIDATION_FAILED: () => {
          const fe = fieldErrors(problem)
          if (Object.keys(fe).length > 0) {
            fieldErr.value = fe
          } else {
            ui.pushToast('error', problem.detail ?? '参数有误，请检查输入')
          }
        },
      }, () => ui.pushToast('error', '修改失败，请稍后再试'))
    } else {
      ui.pushToast('error', '修改失败，请稍后再试')
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="h-full overflow-y-auto">
    <div class="max-w-xl mx-auto p-6 space-y-6">
      <!-- 标题区 -->
      <div>
        <h1 class="font-serif text-lg font-semibold text-deep-charcoal">账户安全</h1>
        <p class="text-xs text-stone-grey mt-1 leading-relaxed">
          修改登录密码。修改成功后所有设备需要重新登录。
        </p>
      </div>

      <!-- 默认密码待办提示 -->
      <Banner v-if="usingDefault" variant="warn">
        仍在使用默认密码 admin / admin123456，建议立即修改。
      </Banner>

      <!-- 改密表单 -->
      <form
        class="bg-white rounded-2xl border border-border hygge-card p-6 space-y-4"
        @submit.prevent="handleSubmit"
      >
        <!-- 当前密码 -->
        <div class="space-y-1.5">
          <label for="currentPassword" class="block text-xs font-medium text-stone-grey">
            当前密码
          </label>
          <input
            id="currentPassword"
            v-model="currentPassword"
            type="password"
            class="w-full hygge-input rounded-xl px-3.5 py-2.5 text-sm"
            placeholder="请输入当前密码"
            autocomplete="current-password"
            :disabled="loading"
          />
          <p v-if="fieldErr.currentPassword" class="text-xs text-danger-text flex items-center gap-1.5">
            <i class="fa-solid fa-circle-exclamation shrink-0" />
            {{ fieldErr.currentPassword }}
          </p>
        </div>

        <!-- 新密码 -->
        <div class="space-y-1.5">
          <label for="newPassword" class="block text-xs font-medium text-stone-grey">
            新密码
          </label>
          <input
            id="newPassword"
            v-model="newPassword"
            type="password"
            class="w-full hygge-input rounded-xl px-3.5 py-2.5 text-sm"
            placeholder="至少 8 个字符"
            autocomplete="new-password"
            :disabled="loading"
          />
          <p v-if="fieldErr.newPassword" class="text-xs text-danger-text flex items-center gap-1.5">
            <i class="fa-solid fa-circle-exclamation shrink-0" />
            {{ fieldErr.newPassword }}
          </p>
        </div>

        <!-- 确认新密码 -->
        <div class="space-y-1.5">
          <label for="confirmPassword" class="block text-xs font-medium text-stone-grey">
            确认新密码
          </label>
          <input
            id="confirmPassword"
            v-model="confirmPassword"
            type="password"
            class="w-full hygge-input rounded-xl px-3.5 py-2.5 text-sm"
            placeholder="再次输入新密码"
            autocomplete="new-password"
            :disabled="loading"
          />
          <p v-if="fieldErr.confirmPassword" class="text-xs text-danger-text flex items-center gap-1.5">
            <i class="fa-solid fa-circle-exclamation shrink-0" />
            {{ fieldErr.confirmPassword }}
          </p>
        </div>

        <!-- 提交 -->
        <button
          type="submit"
          class="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl bg-dark-stone text-warm-white text-sm font-medium hover:bg-deep-charcoal transition disabled:opacity-50 disabled:cursor-not-allowed"
          :disabled="loading || !currentPassword || !newPassword || !confirmPassword"
        >
          <Spin v-if="loading" size="text-sm" />
          <span>{{ loading ? '提交中…' : '修改密码' }}</span>
        </button>
      </form>
    </div>
  </div>
</template>
