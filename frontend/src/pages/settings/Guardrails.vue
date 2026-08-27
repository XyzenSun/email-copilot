<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import { useUiStore } from '@/stores/ui'
import { api } from '@/api/client'
import { isProblem, onCode, fieldErrors } from '@/api/errors'
import type { components } from '@/api/types.gen'
import Spin from '@/components/ui/Spin.vue'

/**
 * 系统参数配置页（FRONTEND.md §3.7）。
 *
 * 9 个用户可调参数，改完立即生效（PATCH 成功 toast），不需要重启。
 * 范围由数据库 check 约束兜底（见 V1__init.sql），应用层同时校验以返回带字段名的错误。
 *
 * 用 settingsStore 缓存：进页面前 fetchGuardrails() 取缓存，PATCH 成功 applyGuardrails(data) 覆盖。
 *
 * GUARDRAIL_OUT_OF_RANGE（422）**不带 errors[] 扩展成员**——后端把字段信息放在 detail
 * 字符串里（如 "initialSyncDays 必须在 1 到 365 之间"）。因此前端做范围校验提供字段级反馈，
 * 后端的 GUARDRAIL_OUT_OF_RANGE 作为兜底（显示 detail toast）。
 * VALIDATION_FAILED（400）带 errors[]，用 fieldErrors 映射到输入框。
 */
type Guardrails = components['schemas']['Guardrails']
type GuardrailsUpdateRequest = components['schemas']['GuardrailsUpdateRequest']

/** 9 个数值护栏字段（不含阶段15 的 2 开关 + 2 数值生命周期字段）。 */
type NumberGuardrailKey =
  | 'initialSyncDays'
  | 'threadSizeLimit'
  | 'processingRetryLimit'
  | 'searchResultLimit'
  | 'turnModelCallLimit'
  | 'turnTimeoutSeconds'
  | 'pendingActionTtlHours'
  | 'toolTimeoutSeconds'
  | 'smtpTimeoutSeconds'

const settings = useSettingsStore()
const ui = useUiStore()

/** 护栏字段配置：label / 范围 / 单位 / 生效说明 / 超时警告。范围来自 DB check 约束。 */
interface GuardrailFieldConfig {
  key: NumberGuardrailKey
  label: string
  min: number
  max: number
  unit: string
  /** 生效时机说明（如「只影响之后新建的提案」） */
  effectNote?: string
  /** 超时参数的警告文字 */
  warning?: string
}

const FIELDS: readonly GuardrailFieldConfig[] = [
  {
    key: 'initialSyncDays',
    label: '首次接入回溯天数',
    min: 1,
    max: 365,
    unit: '天',
    effectNote: '只影响此后新加入账号的首次同步',
  },
  {
    key: 'threadSizeLimit',
    label: '单会话规模上限',
    min: 10,
    max: 10000,
    unit: '封',
  },
  {
    key: 'processingRetryLimit',
    label: '处理重试上限',
    min: 0,
    max: 20,
    unit: '次',
  },
  {
    key: 'searchResultLimit',
    label: '检索返回上限',
    min: 1,
    max: 200,
    unit: '条',
  },
  {
    key: 'turnModelCallLimit',
    label: '单轮模型调用上限',
    min: 1,
    max: 50,
    unit: '次',
  },
  {
    key: 'turnTimeoutSeconds',
    label: '整轮超时',
    min: 10,
    max: 1800,
    unit: '秒',
    effectNote: '立即作用于正在进行的对话轮次',
  },
  {
    key: 'pendingActionTtlHours',
    label: '提案有效期',
    min: 1,
    max: 720,
    unit: '小时',
    effectNote: '只影响之后新建的提案——已存在的过期时刻已落库、不重算',
  },
  {
    key: 'toolTimeoutSeconds',
    label: '单次工具超时',
    min: 5,
    max: 300,
    unit: '秒',
    warning: '调太小会让 IMAP 检索一类的慢工具永远超时，表现为「AI 什么都查不到」而不是报错',
  },
  {
    key: 'smtpTimeoutSeconds',
    label: 'SMTP 超时',
    min: 5,
    max: 300,
    unit: '秒',
    warning:
      '调太小会让正常发信被判「结果不确定」——信实际发出去了但系统无记录，用户以为没发又发一遍',
  },
]

const loading = ref(false)
const saving = ref(false)
/** 原始值快照，用于 diff 出改动字段（PATCH 只传要改的） */
const original = ref<Guardrails | null>(null)
/** 字段级错误映射 */
const fieldErr = ref<Record<string, string>>({})

// 表单值用 string 初始化，但 Vue 3.5 对 <input type="number"> 的 v-model 会在用户输入时
// 自动把值转成 number（castToNumber，源码 vModelText.created），即便不加 .number 修饰符。
// 因此读取时必须 String() 强转回 string 再 trim，否则用户改过值后 .trim() 报
// "trim is not a function"（number 无 trim）。
const form = reactive<{ [K in NumberGuardrailKey]: string }>({
  initialSyncDays: '',
  threadSizeLimit: '',
  processingRetryLimit: '',
  searchResultLimit: '',
  turnModelCallLimit: '',
  turnTimeoutSeconds: '',
  pendingActionTtlHours: '',
  toolTimeoutSeconds: '',
  smtpTimeoutSeconds: '',
})

// ── 邮件生命周期（阶段15）：2 开关 + 2 数值 ──

/** 生命周期数值字段配置（同 FIELDS 模式，单独分组渲染）。 */
interface LifecycleFieldConfig extends Omit<GuardrailFieldConfig, 'key'> {
  key: 'imapSyncIntervalSeconds' | 'messageRetentionDays'
}

const LIFECYCLE_FIELDS: readonly LifecycleFieldConfig[] = [
  {
    key: 'imapSyncIntervalSeconds',
    label: '自动同步间隔',
    min: 30,
    max: 3600,
    unit: '秒',
    effectNote: '改完下一个检查周期（10 秒内）即生效，无需重启',
  },
  {
    key: 'messageRetentionDays',
    label: '邮件保留期',
    min: 1,
    max: 3650,
    unit: '天',
    effectNote: '只清理收件；发件与草稿永久保留',
    warning: '到期邮件被彻底删除且无法恢复，服务器原件仍在',
  },
]

/** 开关用 boolean（checkbox），数值用 string（同样受 Vue 3.5 number 自动转换影响，读取时 String() 强转）。 */
const lifecycleForm = reactive({
  autoSyncEnabled: true,
  imapSyncIntervalSeconds: '',
  autoDeleteEnabled: true,
  messageRetentionDays: '',
})

async function loadGuardrails(): Promise<void> {
  loading.value = true
  fieldErr.value = {}
  try {
    // settingsStore 缓存为 Record<string, unknown>，运行时是 Guardrails 结构
    const data = (await settings.fetchGuardrails()) as Guardrails | null
    if (data) {
      original.value = { ...data }
      for (const f of FIELDS) {
        form[f.key] = String(data[f.key])
      }
      // 生命周期：开关用 boolean，数值用 string
      lifecycleForm.autoSyncEnabled = data.autoSyncEnabled
      lifecycleForm.autoDeleteEnabled = data.autoDeleteEnabled
      lifecycleForm.imapSyncIntervalSeconds = String(data.imapSyncIntervalSeconds)
      lifecycleForm.messageRetentionDays = String(data.messageRetentionDays)
    }
  } catch {
    // 401 由全局 middleware 跳登录；其余异常保守留空表单
  } finally {
    loading.value = false
  }
}

/** 前端范围校验：逐字段检查 min/max，填充 fieldErr。返回是否有错误。 */
function validate(): boolean {
  fieldErr.value = {}
  for (const f of FIELDS) {
    const raw = String(form[f.key]).trim()
    if (raw === '') {
      // 空值不提交（保持原值），不算错误
      continue
    }
    const num = Number(raw)
    if (!Number.isInteger(num)) {
      fieldErr.value[f.key] = '请输入整数'
      continue
    }
    if (num < f.min || num > f.max) {
      fieldErr.value[f.key] = `必须在 ${f.min} 到 ${f.max} 之间`
    }
  }
  // 生命周期数值字段同模式校验
  for (const f of LIFECYCLE_FIELDS) {
    const raw = String(lifecycleForm[f.key]).trim()
    if (raw === '') continue
    const num = Number(raw)
    if (!Number.isInteger(num)) {
      fieldErr.value[f.key] = '请输入整数'
      continue
    }
    if (num < f.min || num > f.max) {
      fieldErr.value[f.key] = `必须在 ${f.min} 到 ${f.max} 之间`
    }
  }
  return Object.keys(fieldErr.value).length === 0
}

/** 构建只含改动字段的 patch body。空值字段不提交（保持原值）。 */
function buildPatch(): GuardrailsUpdateRequest | null {
  const patch: GuardrailsUpdateRequest = {}
  let hasChanges = false
  for (const f of FIELDS) {
    const raw = String(form[f.key]).trim()
    if (raw === '') continue
    const num = Number(raw)
    if (original.value && num !== original.value[f.key]) {
      patch[f.key] = num
      hasChanges = true
    }
  }
  if (original.value) {
    // 生命周期开关：boolean 直接 diff
    if (lifecycleForm.autoSyncEnabled !== original.value.autoSyncEnabled) {
      patch.autoSyncEnabled = lifecycleForm.autoSyncEnabled
      hasChanges = true
    }
    if (lifecycleForm.autoDeleteEnabled !== original.value.autoDeleteEnabled) {
      patch.autoDeleteEnabled = lifecycleForm.autoDeleteEnabled
      hasChanges = true
    }
    // 生命周期数值：空值不提交
    const interval = String(lifecycleForm.imapSyncIntervalSeconds).trim()
    if (interval !== '' && Number(interval) !== original.value.imapSyncIntervalSeconds) {
      patch.imapSyncIntervalSeconds = Number(interval)
      hasChanges = true
    }
    const retention = String(lifecycleForm.messageRetentionDays).trim()
    if (retention !== '' && Number(retention) !== original.value.messageRetentionDays) {
      patch.messageRetentionDays = Number(retention)
      hasChanges = true
    }
  }
  return hasChanges ? patch : null
}

async function handleSubmit(): Promise<void> {
  if (saving.value) return
  if (!validate()) return

  const patch = buildPatch()
  if (!patch) {
    ui.pushToast('info', '没有改动')
    return
  }

  saving.value = true
  try {
    const { data, error } = await api.PATCH('/settings/guardrails', { body: patch })
    if (error || !data) return
    // 用响应覆盖本地缓存 + 表单快照
    settings.applyGuardrails(data as Record<string, unknown>)
    original.value = { ...data }
    for (const f of FIELDS) {
      form[f.key] = String(data[f.key])
    }
    lifecycleForm.autoSyncEnabled = data.autoSyncEnabled
    lifecycleForm.autoDeleteEnabled = data.autoDeleteEnabled
    lifecycleForm.imapSyncIntervalSeconds = String(data.imapSyncIntervalSeconds)
    lifecycleForm.messageRetentionDays = String(data.messageRetentionDays)
    fieldErr.value = {}
    ui.pushToast('success', '系统参数配置已更新，立即生效')
  } catch (err) {
    if (isProblem(err)) {
      const problem = err
      onCode(problem, {
        // 422 不带 errors[]：detail 里有字段名+范围，直接 toast
        GUARDRAIL_OUT_OF_RANGE: () => {
          ui.pushToast('error', problem.detail ?? '系统参数配置超出允许范围')
        },
        // 400 带 errors[]：映射到输入框
        VALIDATION_FAILED: () => {
          const fe = fieldErrors(problem)
          if (Object.keys(fe).length > 0) {
            fieldErr.value = fe
          } else {
            ui.pushToast('error', problem.detail ?? '参数有误')
          }
        },
      }, () => ui.pushToast('error', '更新失败，请稍后再试'))
    } else {
      ui.pushToast('error', '更新失败，请稍后再试')
    }
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void loadGuardrails()
})
</script>

<template>
  <div class="h-full overflow-y-auto">
    <div class="max-w-2xl mx-auto p-6 space-y-6">
      <!-- 标题区 -->
      <div>
        <h1 class="font-serif text-lg font-semibold text-deep-charcoal">系统参数配置</h1>
        <p class="text-xs text-stone-grey mt-1 leading-relaxed">
          系统参数与邮件生命周期自动化，改完立即生效。
        </p>
      </div>

      <!-- 加载中 -->
      <div v-if="loading" class="flex items-center justify-center py-12">
        <Spin size="text-lg" />
      </div>

      <!-- 表单 -->
      <form v-else class="bg-white rounded-2xl border border-border hygge-card p-6 space-y-5" @submit.prevent="handleSubmit">
        <div v-for="f in FIELDS" :key="f.key" class="space-y-1.5">
          <div class="flex items-baseline justify-between">
            <label :for="f.key" class="text-xs font-medium text-stone-grey">{{ f.label }}</label>
            <span class="text-[10px] text-ink-400 font-mono">{{ f.min }} – {{ f.max }} {{ f.unit }}</span>
          </div>
          <div class="flex items-center gap-2">
            <input
              :id="f.key"
              v-model="form[f.key]"
              type="number"
              :min="f.min"
              :max="f.max"
              step="1"
              class="w-32 hygge-input rounded-xl px-3.5 py-2 text-sm"
              :disabled="saving"
            />
            <span class="text-xs text-ink-400">{{ f.unit }}</span>
          </div>
          <!-- 字段级错误 -->
          <p v-if="fieldErr[f.key]" class="text-xs text-danger-text flex items-center gap-1.5">
            <i class="fa-solid fa-circle-exclamation shrink-0" />
            {{ fieldErr[f.key] }}
          </p>
          <!-- 生效时机说明 -->
          <p v-if="f.effectNote" class="text-[10px] text-ink-400 leading-relaxed">
            {{ f.effectNote }}
          </p>
          <!-- 超时警告 -->
          <div v-if="f.warning" class="flex items-start gap-1.5 text-[10px] text-warn-text leading-relaxed bg-warn-bg/50 border border-warn-border rounded-lg px-2.5 py-1.5">
            <i class="fa-solid fa-triangle-exclamation shrink-0 mt-0.5" />
            <span>{{ f.warning }}</span>
          </div>
        </div>

        <!-- 邮件生命周期（阶段15）：2 开关 + 2 数值 -->
        <div class="pt-4 border-t border-border-soft space-y-5">
          <div>
            <h2 class="text-sm font-medium text-deep-charcoal">邮件生命周期</h2>
            <p class="text-[10px] text-ink-400 mt-1">自动拉取新邮件、到期自动清理</p>
          </div>

          <!-- 开关：自动同步 -->
          <label class="flex items-center justify-between gap-4 cursor-pointer">
            <div class="min-w-0">
              <span class="text-xs font-medium text-stone-grey block">自动同步</span>
              <span class="text-[10px] text-ink-400 leading-relaxed block mt-0.5">
                开启后每过一个间隔自动拉取所有已启用邮箱的新邮件
              </span>
            </div>
            <input
              v-model="lifecycleForm.autoSyncEnabled"
              type="checkbox"
              class="w-4 h-4 accent-dark-stone shrink-0"
              :disabled="saving"
            />
          </label>

          <!-- 开关：自动清理 -->
          <label class="flex items-center justify-between gap-4 cursor-pointer">
            <div class="min-w-0">
              <span class="text-xs font-medium text-stone-grey block">自动清理过期邮件</span>
              <span class="text-[10px] text-ink-400 leading-relaxed block mt-0.5">
                每天清理一次保留期之前的收件
              </span>
            </div>
            <input
              v-model="lifecycleForm.autoDeleteEnabled"
              type="checkbox"
              class="w-4 h-4 accent-dark-stone shrink-0"
              :disabled="saving"
            />
          </label>

          <!-- 数值字段（复用 FIELDS 渲染结构） -->
          <div v-for="f in LIFECYCLE_FIELDS" :key="f.key" class="space-y-1.5">
            <div class="flex items-baseline justify-between">
              <label :for="f.key" class="text-xs font-medium text-stone-grey">{{ f.label }}</label>
              <span class="text-[10px] text-ink-400 font-mono">{{ f.min }} – {{ f.max }} {{ f.unit }}</span>
            </div>
            <div class="flex items-center gap-2">
              <input
                :id="f.key"
                v-model="lifecycleForm[f.key]"
                type="number"
                :min="f.min"
                :max="f.max"
                step="1"
                class="w-32 hygge-input rounded-xl px-3.5 py-2 text-sm"
                :disabled="saving"
              />
              <span class="text-xs text-ink-400">{{ f.unit }}</span>
            </div>
            <p v-if="fieldErr[f.key]" class="text-xs text-danger-text flex items-center gap-1.5">
              <i class="fa-solid fa-circle-exclamation shrink-0" />
              {{ fieldErr[f.key] }}
            </p>
            <p v-if="f.effectNote" class="text-[10px] text-ink-400 leading-relaxed">
              {{ f.effectNote }}
            </p>
            <div v-if="f.warning" class="flex items-start gap-1.5 text-[10px] text-warn-text leading-relaxed bg-warn-bg/50 border border-warn-border rounded-lg px-2.5 py-1.5">
              <i class="fa-solid fa-triangle-exclamation shrink-0 mt-0.5" />
              <span>{{ f.warning }}</span>
            </div>
          </div>
        </div>

        <!-- 提交 -->
        <div class="pt-2 border-t border-border-soft">
          <button
            type="submit"
            class="flex items-center gap-2 px-4 py-2.5 rounded-xl bg-dark-stone text-warm-white text-sm font-medium hover:bg-deep-charcoal transition disabled:opacity-50 disabled:cursor-not-allowed"
            :disabled="saving || loading"
          >
            <Spin v-if="saving" size="text-sm" />
            <span>{{ saving ? '保存中…' : '保存' }}</span>
          </button>
        </div>
      </form>
    </div>
  </div>
</template>
