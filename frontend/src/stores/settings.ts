/**
 * settingsStore —— 系统参数配置缓存。FRONTEND.md §5。
 *
 * 只缓存 guardrails（GET /settings/guardrails 的结果，改动后用响应覆盖）。
 * 不缓存凭据/key/主密钥（永不回显，无可缓存）。
 * 其余设置页（system/pipeline 等）进页面即取，不在此缓存。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'

export const useSettingsStore = defineStore('settings', () => {
  /** 系统参数配置缓存；null=未加载 */
  const guardrails = ref<Record<string, unknown> | null>(null)
  const guardrailsLoading = ref(false)

  /** 取系统参数配置；已缓存则不重复请求（除非 force）。 */
  async function fetchGuardrails(force = false): Promise<Record<string, unknown> | null> {
    if (guardrails.value && !force) {
      return guardrails.value
    }
    guardrailsLoading.value = true
    const { data, error } = await api.GET('/settings/guardrails')
    guardrailsLoading.value = false
    if (error) {
      throw error
    }
    guardrails.value = data as Record<string, unknown>
    return guardrails.value
  }

  /** PATCH 成功后用响应体覆盖本地缓存。 */
  function applyGuardrails(next: Record<string, unknown>): void {
    guardrails.value = next
  }

  return {
    guardrails,
    guardrailsLoading,
    fetchGuardrails,
    applyGuardrails,
  }
})
