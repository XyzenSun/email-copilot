<script setup lang="ts">
/**
 * AI 开关与垃圾评分页（FRONTEND.md §3.7）。
 *
 * 六个开关 + 垃圾分类阈值 + 垃圾评分政策 prompt。改完立即生效，只影响后续新邮件，
 * 不回溯已处理邮件（GET/PATCH /settings/pipeline）。
 *
 * 五个邮件流水线开关 + 一个独立会话摘要开关（threadSummaryEnabled，非流水线阶段）。
 * PATCH 全字段可选，只传要改的；显式 null / 空白 prompt / 阈值越界 / 精度超三位小数
 * 返回 400 VALIDATION_FAILED。
 */
import { reactive, ref, computed, onMounted } from 'vue'
import { api } from '@/api/client'
import { isProblem, onCode } from '@/api/errors'
import { useUiStore } from '@/stores/ui'
import type { components } from '@/api/types.gen'
import Spin from '@/components/ui/Spin.vue'

type PipelineSettings = components['schemas']['PipelineSettings']
type PipelineSettingsUpdateRequest = components['schemas']['PipelineSettingsUpdateRequest']

type SwitchKey =
  | 'spamCheckEnabled'
  | 'classifyEnabled'
  | 'taggingEnabled'
  | 'languageTranslationEnabled'
  | 'summaryEnabled'
  | 'threadSummaryEnabled'

/** 开关配置：label + 关掉后的后果（只说操作后果，不说机制）。 */
const SWITCHES: { key: SwitchKey; label: string; offEffect: string }[] = [
  { key: 'spamCheckEnabled', label: '垃圾检查', offEffect: '关掉后只有命中屏蔽规则的邮件才判为垃圾，其余不评分' },
  { key: 'classifyEnabled', label: '自动分类', offEffect: '关掉后邮件不分类，分类筛选栏仍显示但点开为空' },
  { key: 'taggingEnabled', label: '自动标签', offEffect: '关掉后不自动打标签，手动打标签不受影响' },
  { key: 'languageTranslationEnabled', label: '语言判断与翻译', offEffect: '关掉后不判断语言、不翻译非中文邮件' },
  { key: 'summaryEnabled', label: '邮件摘要', offEffect: '关掉后邮件无摘要，列表预览回退到正文开头' },
  { key: 'threadSummaryEnabled', label: '会话摘要', offEffect: '关掉后会话页不显示摘要' },
]

const ui = useUiStore()
const settings = ref<PipelineSettings | null>(null)
const original = ref<PipelineSettings | null>(null)
const loading = ref(true)
const saving = ref(false)
const fieldErr = ref<Record<string, string>>({})

// 表单值：开关用 boolean，阈值用 string（input 原生值，提交时 parse），prompt 用 string
const switchForm = reactive<Record<SwitchKey, boolean>>({
  spamCheckEnabled: false,
  classifyEnabled: false,
  taggingEnabled: false,
  languageTranslationEnabled: false,
  summaryEnabled: false,
  threadSummaryEnabled: false,
})
const threshold = ref('')
const prompt = ref('')

async function load(): Promise<void> {
  loading.value = true
  fieldErr.value = {}
  try {
    const { data, error } = await api.GET('/settings/pipeline')
    if (error || !data) return
    settings.value = data
    original.value = { ...data }
    for (const s of SWITCHES) switchForm[s.key] = data[s.key]
    threshold.value = String(data.spamClassificationThreshold)
    prompt.value = data.spamJudgmentPrompt
  } finally {
    loading.value = false
  }
}

/** 前端校验阈值：0–1、精度 ≤ 3 位小数。空值保持原值不算错误。 */
function validate(): boolean {
  fieldErr.value = {}
  const raw = threshold.value.trim()
  if (raw === '') return true
  const num = Number(raw)
  if (Number.isNaN(num) || num < 0 || num > 1) {
    fieldErr.value.spamClassificationThreshold = '阈值必须在 0 到 1 之间'
    return false
  }
  const decimals = raw.split('.')[1]
  if (decimals && decimals.length > 3) {
    fieldErr.value.spamClassificationThreshold = '精度不超过 3 位小数'
    return false
  }
  return true
}

/** 构建只含改动字段的 patch body。空阈值字段不提交（保持原值）。 */
function buildPatch(): PipelineSettingsUpdateRequest | null {
  if (!original.value) return null
  const patch: PipelineSettingsUpdateRequest = {}
  let hasChanges = false
  for (const s of SWITCHES) {
    if (switchForm[s.key] !== original.value[s.key]) {
      patch[s.key] = switchForm[s.key]
      hasChanges = true
    }
  }
  const raw = threshold.value.trim()
  if (raw !== '') {
    const num = Number(raw)
    if (num !== original.value.spamClassificationThreshold) {
      patch.spamClassificationThreshold = num
      hasChanges = true
    }
  }
  if (prompt.value !== original.value.spamJudgmentPrompt) {
    patch.spamJudgmentPrompt = prompt.value
    hasChanges = true
  }
  return hasChanges ? patch : null
}

/** 是否有改动（保存按钮 disabled 判据） */
const dirty = computed(() => {
  if (!original.value) return false
  for (const s of SWITCHES) {
    if (switchForm[s.key] !== original.value[s.key]) return true
  }
  const raw = threshold.value.trim()
  if (raw !== '' && Number(raw) !== original.value.spamClassificationThreshold) return true
  if (prompt.value !== original.value.spamJudgmentPrompt) return true
  return false
})

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
    const { data, error } = await api.PATCH('/settings/pipeline', { body: patch })
    if (error || !data) return
    // 用响应覆盖本地快照 + 表单
    settings.value = data
    original.value = { ...data }
    for (const s of SWITCHES) switchForm[s.key] = data[s.key]
    threshold.value = String(data.spamClassificationThreshold)
    prompt.value = data.spamJudgmentPrompt
    fieldErr.value = {}
    ui.pushToast('success', '已保存，立即生效')
  } catch (err) {
    if (isProblem(err)) {
      ui.pushToast(
        'error',
        onCode(err, { VALIDATION_FAILED: () => err.detail ?? '参数有误，请检查' }, () => '保存失败') ?? '保存失败',
      )
    } else {
      ui.pushToast('error', '保存失败，请稍后再试')
    }
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="h-full overflow-y-auto">
    <!-- 页头 -->
    <div class="px-6 py-4 border-b border-border bg-warm-white">
      <h2 class="font-serif font-semibold text-base text-deep-charcoal">AI 开关与垃圾评分</h2>
      <p class="text-xs text-ink-400 mt-0.5">
        控制邮件 AI 处理的开关与垃圾评分策略。改完立即生效，只影响后续新邮件。
      </p>
    </div>

    <!-- 加载中 -->
    <div v-if="loading" class="flex items-center justify-center py-16">
      <Spin size="text-xl" />
    </div>

    <div v-else-if="settings" class="p-6 max-w-2xl space-y-5">
      <!-- 处理开关 -->
      <div class="hygge-card bg-white rounded-2xl border border-border p-5">
        <h3 class="text-[10px] font-mono uppercase tracking-wider text-ink-400 font-semibold mb-1">处理开关</h3>
        <div
          v-for="s in SWITCHES"
          :key="s.key"
          class="flex items-start justify-between gap-3 py-2.5 border-b border-border-soft last:border-0"
        >
          <div class="min-w-0">
            <div class="text-xs font-medium text-stone-grey">{{ s.label }}</div>
            <p class="text-[10px] text-ink-400 mt-0.5 leading-relaxed">{{ s.offEffect }}</p>
          </div>
          <button
            type="button"
            class="relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition mt-0.5"
            :class="switchForm[s.key] ? 'bg-sage' : 'bg-border-strong'"
            :disabled="saving"
            @click="switchForm[s.key] = !switchForm[s.key]"
          >
            <span
              class="inline-block h-3.5 w-3.5 transform rounded-full bg-white transition"
              :class="switchForm[s.key] ? 'translate-x-5' : 'translate-x-1'"
            />
          </button>
        </div>
      </div>

      <!-- 垃圾评分 -->
      <div class="hygge-card bg-white rounded-2xl border border-border p-5 space-y-4">
        <h3 class="text-[10px] font-mono uppercase tracking-wider text-ink-400 font-semibold">垃圾评分</h3>

        <!-- 阈值 -->
        <div class="space-y-1.5">
          <label class="block text-xs font-medium text-stone-grey">垃圾分类阈值</label>
          <div class="flex items-center gap-2">
            <input
              v-model="threshold"
              type="number"
              min="0"
              max="1"
              step="0.001"
              class="w-32 hygge-input rounded-xl px-3.5 py-2 text-sm"
              :disabled="saving"
            />
            <span class="text-xs text-ink-400">0 – 1</span>
          </div>
          <p class="text-[10px] text-ink-400 leading-relaxed">垃圾评分 ≥ 此值判为垃圾邮件，精度 3 位小数。</p>
          <p v-if="fieldErr.spamClassificationThreshold" class="text-xs text-danger-text flex items-center gap-1.5">
            <i class="fa-solid fa-circle-exclamation shrink-0" />
            {{ fieldErr.spamClassificationThreshold }}
          </p>
        </div>

        <!-- 评分政策 prompt -->
        <div class="space-y-1.5">
          <label class="block text-xs font-medium text-stone-grey">垃圾评分政策</label>
          <textarea
            v-model="prompt"
            rows="5"
            class="w-full hygge-input rounded-xl px-3 py-2 text-sm resize-y"
            :disabled="saving"
          />
          <p class="text-[10px] text-ink-400 leading-relaxed">定义什么内容应得高分或低分。</p>
        </div>
      </div>

      <!-- 保存 -->
      <div class="pt-1">
        <button
          type="button"
          class="flex items-center gap-2 px-4 py-2.5 rounded-xl bg-dark-stone text-warm-white text-sm font-medium hover:bg-deep-charcoal transition disabled:opacity-50 disabled:cursor-not-allowed"
          :disabled="saving || loading || !dirty"
          @click="handleSubmit"
        >
          <Spin v-if="saving" size="text-sm" />
          <span>{{ saving ? '保存中…' : '保存' }}</span>
        </button>
      </div>
    </div>
  </div>
</template>
