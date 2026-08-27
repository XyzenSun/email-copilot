<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useUiStore } from '@/stores/ui'
import { api } from '@/api/client'
import { isProblem, onCode } from '@/api/errors'
import type { components } from '@/api/types.gen'
import Spin from '@/components/ui/Spin.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import Modal from '@/components/ui/Modal.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import { formatDateTime } from '@/utils/format'

/**
 * 发件人规则页（FRONTEND.md §3.7）。
 *
 * 屏蔽（block）/信任（trust）规则，按已认证域名匹配。模式：
 *   a.com — 精准匹配裸域本身（子域不匹配）
 *   *.a.com — 恰好一层子域名（mail.a.com 匹配，a.com 不匹配）
 *   +.a.com — 任意层含裸域（a.com、x.y.a.com 都匹配）
 * 不用正则（防灾难性回溯）。
 *
 * 「新规则不影响已收到的邮件」——判定在收信时完成，不回溯。
 *
 * enabled 切换即时 PATCH（单字段），pattern/type 编辑走 Modal（创建/编辑共用）。
 */
type SenderRule = components['schemas']['SenderRule']
type RuleType = components['schemas']['RuleType']

const ui = useUiStore()

const rules = ref<SenderRule[]>([])
const loading = ref(true)
const togglingId = ref<number | null>(null)

// 创建/编辑 Modal 状态
const showModal = ref(false)
const editingRule = ref<SenderRule | null>(null)
const formRuleType = ref<RuleType>('block')
const formDomainPattern = ref('')
const formEnabled = ref(true)
const saving = ref(false)
const formError = ref<string | null>(null)

// 删除确认
const deleteTarget = ref<SenderRule | null>(null)

const RULE_TYPE_LABELS: Record<RuleType, string> = {
  block: '屏蔽',
  trust: '信任',
}

async function loadRules(): Promise<void> {
  loading.value = true
  try {
    const { data, error } = await api.GET('/sender-rules')
    if (error || !data) return
    rules.value = data.items
  } catch {
    // 401 由全局 middleware 处理
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editingRule.value = null
  formRuleType.value = 'block'
  formDomainPattern.value = ''
  formEnabled.value = true
  formError.value = null
  showModal.value = true
}

function openEdit(rule: SenderRule): void {
  editingRule.value = rule
  formRuleType.value = rule.ruleType
  formDomainPattern.value = rule.domainPattern
  formEnabled.value = rule.enabled
  formError.value = null
  showModal.value = true
}

async function handleSubmit(): Promise<void> {
  if (saving.value) return
  const pattern = formDomainPattern.value.trim()
  if (!pattern) {
    formError.value = '请输入域名模式'
    return
  }

  saving.value = true
  formError.value = null
  try {
    if (editingRule.value) {
      // 编辑：PATCH
      const { data, error } = await api.PATCH('/sender-rules/{id}', {
        params: { path: { id: editingRule.value.id } },
        body: {
          ruleType: formRuleType.value,
          domainPattern: pattern,
          enabled: formEnabled.value,
        },
      })
      if (error || !data) return
      const idx = rules.value.findIndex((r) => r.id === data.id)
      if (idx >= 0) rules.value[idx] = data
      ui.pushToast('success', '规则已更新')
    } else {
      // 新建：POST
      const { data, error } = await api.POST('/sender-rules', {
        body: {
          ruleType: formRuleType.value,
          domainPattern: pattern,
          enabled: formEnabled.value,
        },
      })
      if (error || !data) return
      rules.value.push(data)
      ui.pushToast('success', '规则已创建')
    }
    showModal.value = false
  } catch (err) {
    if (isProblem(err)) {
      const problem = err
      formError.value =
        onCode(
          problem,
          {
            INVALID_DOMAIN_PATTERN: () => '域名模式非法，支持精准域名、* 与 + 通配，不支持正则',
            SENDER_RULE_DUPLICATE: () => '同类型同模式的规则已存在',
            VALIDATION_FAILED: () => problem.detail ?? '参数有误，请检查输入',
          },
          () => '操作失败，请稍后再试',
        ) ?? '操作失败，请稍后再试'
    } else {
      formError.value = '操作失败，请稍后再试'
    }
  } finally {
    saving.value = false
  }
}

/** enabled 切换：即时 PATCH 单字段。 */
async function toggleEnabled(rule: SenderRule): Promise<void> {
  if (togglingId.value !== null) return
  togglingId.value = rule.id
  try {
    const { data, error } = await api.PATCH('/sender-rules/{id}', {
      params: { path: { id: rule.id } },
      body: { enabled: !rule.enabled },
    })
    if (error || !data) return
    const idx = rules.value.findIndex((r) => r.id === data.id)
    if (idx >= 0) rules.value[idx] = data
  } catch (err) {
    if (isProblem(err)) {
      onCode(
        err,
        {
          SENDER_RULE_NOT_FOUND: () => ui.pushToast('error', '规则不存在，可能已被删除'),
        },
        () => ui.pushToast('error', '更新失败，请稍后再试'),
      )
    } else {
      ui.pushToast('error', '更新失败，请稍后再试')
    }
  } finally {
    togglingId.value = null
  }
}

async function confirmDelete(): Promise<void> {
  if (!deleteTarget.value) return
  const id = deleteTarget.value.id
  try {
    await api.DELETE('/sender-rules/{id}', { params: { path: { id } } })
    rules.value = rules.value.filter((r) => r.id !== id)
    ui.pushToast('success', '规则已删除')
  } catch (err) {
    if (isProblem(err)) {
      onCode(
        err,
        {
          SENDER_RULE_NOT_FOUND: () => ui.pushToast('error', '规则不存在，可能已被删除'),
        },
        () => ui.pushToast('error', '删除失败，请稍后再试'),
      )
    } else {
      ui.pushToast('error', '删除失败，请稍后再试')
    }
  } finally {
    deleteTarget.value = null
  }
}

onMounted(() => {
  void loadRules()
})
</script>

<template>
  <div class="h-full overflow-y-auto">
    <div class="max-w-2xl mx-auto p-6 space-y-6">
      <!-- 标题区 -->
      <div class="flex items-start justify-between">
        <div>
          <h1 class="font-serif text-lg font-semibold text-deep-charcoal">发件人规则</h1>
          <p class="text-xs text-stone-grey mt-1 leading-relaxed">
            按已认证域名屏蔽或信任。block 命中即判 spam 并终止；trust 只豁免垃圾判定，分类仍由 AI 判断。
          </p>
        </div>
        <button
          type="button"
          class="shrink-0 flex items-center gap-1.5 px-3.5 py-2 rounded-xl bg-dark-stone text-warm-white text-xs font-medium hover:bg-deep-charcoal transition"
          @click="openCreate"
        >
          <i class="fa-solid fa-plus text-[10px]" />
          新建规则
        </button>
      </div>

      <!-- 不回溯说明 -->
      <div class="flex items-start gap-2 text-xs text-ink-500 bg-light-beige border border-border rounded-xl px-4 py-3 leading-relaxed">
        <i class="fa-solid fa-circle-info shrink-0 mt-0.5" />
        <span>新规则只影响之后收到的邮件，不回溯判定已收到的邮件。</span>
      </div>

      <!-- 模式说明 -->
      <div class="text-[10px] text-ink-400 font-mono leading-relaxed">
        <code class="text-clay">a.com</code> 精准匹配 ｜ <code class="text-clay">*.a.com</code> 恰好一层子域名 ｜ <code class="text-clay">+.a.com</code> 任意层含裸域 ｜ 不支持正则
      </div>

      <!-- 加载中 -->
      <div v-if="loading" class="flex items-center justify-center py-12">
        <Spin size="text-lg" />
      </div>

      <!-- 空态 -->
      <EmptyState
        v-else-if="rules.length === 0"
        icon="fa-solid fa-shield"
        title="暂无规则"
        description="新建屏蔽或信任规则来控制垃圾判定"
      />

      <!-- 规则列表 -->
      <div v-else class="space-y-2">
        <div
          v-for="rule in rules"
          :key="rule.id"
          class="bg-white rounded-2xl border border-border hygge-card px-4 py-3 flex items-center gap-3"
          :class="{ 'opacity-50': !rule.enabled }"
        >
          <!-- 类型标记 -->
          <span
            class="shrink-0 inline-flex items-center rounded-full border px-2 py-0.5 text-[10px] font-medium"
            :class="
              rule.ruleType === 'block'
                ? 'bg-danger-bg text-danger-text border-danger-border'
                : 'bg-[#f0f4ef] text-sage border-[#dfe6dc]'
            "
          >
            {{ RULE_TYPE_LABELS[rule.ruleType] }}
          </span>

          <!-- 域名模式 -->
          <span class="flex-1 font-mono text-sm text-deep-charcoal truncate">{{ rule.domainPattern }}</span>

          <!-- 更新时间 -->
          <span class="text-[10px] text-ink-400 shrink-0 hidden sm:inline">
            {{ formatDateTime(rule.updatedAt) }}
          </span>

          <!-- enabled 开关 -->
          <button
            type="button"
            class="shrink-0 relative w-9 h-5 rounded-full transition"
            :class="rule.enabled ? 'bg-sage' : 'bg-border-strong'"
            :disabled="togglingId === rule.id"
            :aria-label="rule.enabled ? '已启用，点击禁用' : '已禁用，点击启用'"
            @click="toggleEnabled(rule)"
          >
            <span
              class="absolute top-0.5 left-0.5 w-4 h-4 rounded-full bg-white shadow-sm transition-transform"
              :class="{ 'translate-x-4': rule.enabled }"
            />
          </button>

          <!-- 编辑 -->
          <button
            type="button"
            class="shrink-0 text-xs text-stone-grey hover:text-deep-charcoal transition"
            @click="openEdit(rule)"
          >
            <i class="fa-solid fa-pen" />
          </button>

          <!-- 删除 -->
          <button
            type="button"
            class="shrink-0 text-xs text-ink-400 hover:text-danger-text transition"
            @click="deleteTarget = rule"
          >
            <i class="fa-solid fa-trash" />
          </button>
        </div>
      </div>
    </div>

    <!-- 创建/编辑 Modal -->
    <Modal
      v-model="showModal"
      :title="editingRule ? '编辑规则' : '新建规则'"
    >
      <form class="space-y-4" @submit.prevent="handleSubmit">
        <!-- 类型 -->
        <div class="space-y-1.5">
          <label class="block text-xs font-medium text-stone-grey">类型</label>
          <div class="flex gap-2">
            <button
              v-for="rt in (['block', 'trust'] as const)"
              :key="rt"
              type="button"
              class="flex-1 px-3 py-2 rounded-xl text-xs font-medium transition border"
              :class="
                formRuleType === rt
                  ? rt === 'block'
                    ? 'bg-danger-bg text-danger-text border-danger-border'
                    : 'bg-[#f0f4ef] text-sage border-[#dfe6dc]'
                  : 'bg-white text-stone-grey border-border hover:bg-light-beige'
              "
              @click="formRuleType = rt"
            >
              {{ RULE_TYPE_LABELS[rt] }}
            </button>
          </div>
        </div>

        <!-- 域名模式 -->
        <div class="space-y-1.5">
          <label for="domainPattern" class="block text-xs font-medium text-stone-grey">域名模式</label>
          <input
            id="domainPattern"
            v-model="formDomainPattern"
            type="text"
            class="w-full hygge-input rounded-xl px-3.5 py-2.5 text-sm font-mono"
            placeholder="如 example.com、*.example.com 或 +.example.com"
            autocomplete="off"
            :disabled="saving"
          />
          <p class="text-[10px] text-ink-400 leading-relaxed">
            <code class="text-clay">*.a.com</code> 恰好一层子域名 ｜ <code class="text-clay">+.a.com</code> 任意层含裸域
          </p>
        </div>

        <!-- enabled -->
        <label class="flex items-center gap-2 cursor-pointer">
          <input
            v-model="formEnabled"
            type="checkbox"
            class="w-4 h-4 rounded border-border-strong text-sage focus:ring-sage"
          />
          <span class="text-xs text-stone-grey">启用此规则</span>
        </label>

        <!-- 错误 -->
        <p v-if="formError" class="text-xs text-danger-text flex items-center gap-1.5">
          <i class="fa-solid fa-circle-exclamation shrink-0" />
          {{ formError }}
        </p>
      </form>

      <template #footer>
        <button
          type="button"
          class="px-4 py-2 rounded-xl text-xs font-medium text-stone-grey hover:bg-light-beige transition"
          @click="showModal = false"
        >
          取消
        </button>
        <button
          type="button"
          class="px-4 py-2 rounded-xl text-xs font-medium bg-dark-stone text-warm-white hover:bg-deep-charcoal transition disabled:opacity-50 disabled:cursor-not-allowed"
          :disabled="saving || !formDomainPattern.trim()"
          @click="handleSubmit"
        >
          <Spin v-if="saving" size="text-xs" class="mr-1" />
          {{ editingRule ? '保存' : '创建' }}
        </button>
      </template>
    </Modal>

    <!-- 删除确认 -->
    <ConfirmDialog
      :model-value="deleteTarget !== null"
      title="删除规则"
      :message="deleteTarget ? `确定删除规则「${deleteTarget.domainPattern}」吗？` : ''"
      confirm-text="删除"
      danger
      @confirm="confirmDelete"
      @cancel="deleteTarget = null"
    />
  </div>
</template>
