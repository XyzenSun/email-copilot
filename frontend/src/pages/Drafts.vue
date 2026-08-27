<script setup lang="ts">
/**
 * 草稿页。FRONTEND.md §3.8。
 *
 * 全宽布局（非邮件类路由）：左列草稿列表（w-80）+ 右列编辑器（flex-1）。
 * 三种出身的草稿都在这里：AI 在对话里起草的、在会话页写的回复、从零新建的。
 * **从零新建的唯一入口**——点「新建草稿」→ 必须先选发信邮箱 → POST /drafts。
 *
 * 编辑即时保存（PATCH debounce 800ms）。不能改「回复哪封」「属于哪次对话」
 * （DraftUpdateRequest 不含这两个字段，TypeScript 层面已禁止）。
 *
 * 发送 POST /send 带 draftId：成功草稿自动删（后端删）；失败/不确定保留。
 * AI 润色 POST /drafts/polish：建议文本展示对比，用户点「采用」才写回。
 *
 * 发信 200+status 三态（design §6.3）：
 *   - succeeded → 提示已发送，从列表移除草稿
 *   - failed → 显示 resultMessage，**保留编辑框**
 *   - indeterminate → 提示可能已发出，**绝不清空编辑框**
 */
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { api } from '@/api/client'
import { isProblem, onCode } from '@/api/errors'
import { useUiStore } from '@/stores/ui'
import { useMailAccounts } from '@/composables/useMailAccounts'
import { resolveSendResult } from '@/utils/send'
import {
  parseAddresses,
  joinAddresses,
  formatRecipients,
  type Draft,
} from '@/utils/mail'
import { formatRelative } from '@/utils/format'
import Spin from '@/components/ui/Spin.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import Modal from '@/components/ui/Modal.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'

const ui = useUiStore()
const { mailAccounts, fetchMailAccounts } = useMailAccounts()

// ── 草稿列表 ──
const drafts = ref<Draft[]>([])
const listLoading = ref(true)
const selectedDraft = ref<Draft | null>(null)

// ── 编辑器字段 ──
const editFromAccountId = ref<number | null>(null)
const editTo = ref('')
const editCc = ref('')
const editBcc = ref('')
const editSubject = ref('')
const editBody = ref('')
const showCcBcc = ref(false)

// ── 防抖保存 ──
let skipWatch = false
let dirty = ref(false)
let saveTimer: number | null = null

// ── 发信 ──
const sending = ref(false)

// ── AI 润色 ──
const polishedText = ref<string | null>(null)
const polishLoading = ref(false)

// ── 新建草稿弹窗 ──
const showNewDraft = ref(false)
const newDraftAccountId = ref<number | null>(null)

// ── 删除确认 ──
const showDeleteConfirm = ref(false)

/** 配了发信通道（SMTP）的账号——新建草稿只能选这些 */
const smtpEnabledAccounts = computed(() =>
  mailAccounts.value.filter((a) => a.smtpEnabled),
)

/** 加载草稿列表 */
async function loadDrafts(): Promise<void> {
  listLoading.value = true
  try {
    const { data, error } = await api.GET('/drafts')
    if (error || !data) return
    drafts.value = data.items
    // 自动选中第一个（若有）
    if (drafts.value.length > 0 && !selectedDraft.value) {
      selectDraft(drafts.value[0]!)
    }
  } finally {
    listLoading.value = false
  }
}

/** 选中草稿，加载到编辑器 */
function selectDraft(d: Draft): void {
  cancelPolish()
  skipWatch = true
  selectedDraft.value = d
  editFromAccountId.value = d.fromMailAccountId
  editTo.value = joinAddresses(d.recipients.to)
  editCc.value = joinAddresses(d.recipients.cc)
  editBcc.value = joinAddresses(d.recipients.bcc)
  showCcBcc.value = d.recipients.cc.length > 0 || d.recipients.bcc.length > 0
  editSubject.value = d.subject
  editBody.value = d.bodyText
  dirty.value = false
  // nextTick 后恢复 watch（等 Vue 更新完 DOM）
  void Promise.resolve().then(() => {
    skipWatch = false
  })
}

/** 监听编辑器字段变化 → 标记 dirty + 防抖保存 */
watch(
  [editFromAccountId, editTo, editCc, editBcc, editSubject, editBody],
  () => {
    if (skipWatch) return
    dirty.value = true
    scheduleSave()
  },
)

function scheduleSave(): void {
  if (saveTimer != null) window.clearTimeout(saveTimer)
  saveTimer = window.setTimeout(() => {
    void doSave()
  }, 800)
}

/** 防抖 PATCH 保存 */
async function doSave(): Promise<void> {
  if (!dirty.value || !selectedDraft.value) return
  if (editFromAccountId.value == null) return
  const draftId = selectedDraft.value.id
  dirty.value = false
  try {
    const { data, error } = await api.PATCH('/drafts/{id}', {
      params: { path: { id: draftId } },
      body: {
        fromMailAccountId: editFromAccountId.value,
        recipients: {
          to: parseAddresses(editTo.value),
          cc: parseAddresses(editCc.value),
          bcc: parseAddresses(editBcc.value),
        },
        subject: editSubject.value,
        bodyText: editBody.value,
      },
    })
    if (error || !data) {
      dirty.value = true
      return
    }
    // 用服务端响应更新本地（updatedAt 等）
    if (selectedDraft.value?.id === draftId) {
      selectedDraft.value = data
    }
    const idx = drafts.value.findIndex((d) => d.id === draftId)
    if (idx >= 0) drafts.value[idx] = data
  } catch {
    // 保存失败：标记 dirty 允许重试
    dirty.value = true
  }
}

/** 新建草稿 */
async function createDraft(): Promise<void> {
  if (newDraftAccountId.value == null) return
  try {
    const { data, error } = await api.POST('/drafts', {
      body: {
        fromMailAccountId: newDraftAccountId.value,
        recipients: { to: [], cc: [], bcc: [] },
        subject: '',
        bodyText: '',
      },
    })
    if (error || !data) return
    drafts.value.unshift(data)
    showNewDraft.value = false
    newDraftAccountId.value = null
    selectDraft(data)
  } catch (e) {
    if (isProblem(e)) {
      ui.pushToast('error', onCode(e, {
        MAIL_ACCOUNT_NOT_FOUND: () => '发信账号不存在',
        VALIDATION_FAILED: () => '参数有误',
      }, () => '新建草稿失败') ?? '新建草稿失败')
    }
  }
}

/** 发送草稿（POST /send 带 draftId）。200+status 三态 */
async function sendDraft(): Promise<void> {
  if (!selectedDraft.value || sending.value) return
  if (editFromAccountId.value == null) {
    ui.pushToast('error', '请先选择发信邮箱')
    return
  }
  const recipients = parseAddresses(editTo.value)
  if (recipients.length === 0) {
    ui.pushToast('error', '请填写至少一个收件人')
    return
  }
  sending.value = true
  const draftId = selectedDraft.value.id
  try {
    const { data, error } = await api.POST('/send', {
      body: {
        fromMailAccountId: editFromAccountId.value,
        draftId,
        recipients: {
          to: recipients,
          cc: parseAddresses(editCc.value),
          bcc: parseAddresses(editBcc.value),
        },
        subject: editSubject.value,
        bodyText: editBody.value,
      },
    })
    if (error || !data) {
      ui.pushToast('error', '发送请求异常')
      return
    }
    // 200+status 三态
    const outcome = resolveSendResult(data.status, data.resultMessage)
    ui.pushToast(outcome.toastType, outcome.toastMessage)
    if (outcome.shouldRefresh) {
      // succeeded：后端已删草稿，从列表移除
      drafts.value = drafts.value.filter((d) => d.id !== draftId)
      selectedDraft.value = null
      editBody.value = ''
    }
    // failed / indeterminate：保留编辑框，不清空
  } catch (e) {
    if (isProblem(e)) {
      const msg = onCode(e, {
        SMTP_NOT_CONFIGURED: () => '发信账号未配置 SMTP',
        INVALID_RECIPIENT_ADDRESS: () => '收件人地址无效',
        VALIDATION_FAILED: () => '参数有误：' + (e.detail ?? ''),
        DRAFT_NOT_FOUND: () => '草稿不存在',
        MAIL_ACCOUNT_NOT_FOUND: () => '发信账号不存在',
      }, () => '发送失败') ?? '发送失败'
      ui.pushToast('error', msg)
    } else {
      ui.pushToast('error', '发送失败，请稍后再试')
    }
  } finally {
    sending.value = false
  }
}

/** AI 润色 */
async function polishDraft(): Promise<void> {
  if (!editBody.value.trim() || polishLoading.value) return
  polishLoading.value = true
  try {
    const { data, error } = await api.POST('/drafts/polish', {
      body: { bodyText: editBody.value },
    })
    if (error || !data) return
    polishedText.value = data.polishedText
  } catch (e) {
    if (isProblem(e)) {
      ui.pushToast('error', onCode(e, {
        AI_PROVIDER_UNAVAILABLE: () => 'AI 服务暂时不可用',
      }, () => '润色失败') ?? '润色失败')
    }
  } finally {
    polishLoading.value = false
  }
}

function adoptPolished(): void {
  if (polishedText.value) {
    editBody.value = polishedText.value
    polishedText.value = null
  }
}

function cancelPolish(): void {
  polishedText.value = null
}

/** 删除草稿 */
async function doDeleteDraft(): Promise<void> {
  if (!selectedDraft.value) return
  const draftId = selectedDraft.value.id
  try {
    await api.DELETE('/drafts/{id}', {
      params: { path: { id: draftId } },
    })
    drafts.value = drafts.value.filter((d) => d.id !== draftId)
    selectedDraft.value = null
    editBody.value = ''
    ui.pushToast('success', '草稿已删除')
  } catch {
    ui.pushToast('error', '删除草稿失败')
  }
}

onMounted(async () => {
  await fetchMailAccounts()
  await loadDrafts()
})

// 组件卸载时清掉待执行的防抖保存定时器，避免离开页面后发无意义的 PATCH
onUnmounted(() => {
  if (saveTimer != null) window.clearTimeout(saveTimer)
})
</script>

<template>
  <div class="flex h-full overflow-hidden">
    <!-- 左列：草稿列表 -->
    <div class="w-80 border-r border-border bg-warm-white flex flex-col overflow-hidden shrink-0">
      <div class="px-3 py-2.5 border-b border-border flex items-center justify-between shrink-0">
        <span class="font-serif font-semibold text-sm text-deep-charcoal">草稿箱</span>
        <button
          type="button"
          class="flex items-center gap-1.5 px-2.5 py-1 rounded-xl text-xs font-medium bg-dark-stone text-warm-white hover:bg-deep-charcoal transition"
          @click="showNewDraft = true"
        >
          <i class="fa-solid fa-plus text-[10px]" /> 新建
        </button>
      </div>

      <div class="flex-1 overflow-y-auto">
        <div v-if="listLoading" class="flex items-center justify-center py-12">
          <Spin size="text-xl" />
        </div>
        <EmptyState
          v-else-if="drafts.length === 0"
          icon="fa-regular fa-pen-to-square"
          title="没有草稿"
          description="点击右上角「新建」创建草稿"
        />
        <template v-else>
          <div
            v-for="d in drafts"
            :key="d.id"
            :class="[
              'px-3.5 py-3 border-b border-border-soft cursor-pointer transition',
              selectedDraft?.id === d.id ? 'list-pick bg-white shadow-sm' : 'hover:bg-light-beige/50',
            ]"
            @click="selectDraft(d)"
          >
            <div class="flex items-center justify-between gap-2">
              <span class="text-xs font-medium text-dark-stone truncate">
                {{ formatRecipients(d.recipients) || '(无收件人)' }}
              </span>
              <span class="text-[10px] text-ink-400 shrink-0">{{ formatRelative(d.updatedAt) }}</span>
            </div>
            <p class="text-xs text-ink-500 truncate mt-0.5">{{ d.subject || '(无主题)' }}</p>
            <p class="text-[11px] text-ink-400 line-clamp-2 mt-1">{{ d.bodyText || '(空)' }}</p>
            <p v-if="d.inReplyToSubject" class="text-[10px] text-ink-300 mt-1 truncate">
              回复自《{{ d.inReplyToSubject }}》
            </p>
          </div>
        </template>
      </div>
    </div>

    <!-- 右列：编辑器 -->
    <div class="flex-1 flex flex-col overflow-hidden">
      <EmptyState
        v-if="!selectedDraft"
        icon="fa-regular fa-pen-to-square"
        title="选择一个草稿编辑"
        description="从左侧列表选择草稿，或点击「新建」创建"
      />
      <template v-else>
        <!-- 编辑器头部 -->
        <div class="px-5 py-3 border-b border-border flex items-center justify-between shrink-0">
          <div class="flex items-center gap-2 text-xs text-ink-400">
            <span v-if="dirty" class="flex items-center gap-1 text-clay">
              <i class="fa-solid fa-circle text-[5px]" /> 未保存
            </span>
            <span v-else class="flex items-center gap-1 text-sage">
              <i class="fa-solid fa-circle text-[5px]" /> 已保存
            </span>
          </div>
          <div class="flex items-center gap-2">
            <button
              type="button"
              class="text-xs text-ink-300 hover:text-danger-text transition"
              @click="showDeleteConfirm = true"
            >
              <i class="fa-solid fa-trash text-[10px]" /> 删除草稿
            </button>
          </div>
        </div>

        <!-- 编辑器正文 -->
        <div class="flex-1 overflow-y-auto px-5 py-4 space-y-3">
          <!-- 发信邮箱（可改，但只能选配了 SMTP 的账号） -->
          <div class="space-y-1">
            <label class="block text-[10px] font-mono uppercase tracking-wider text-ink-400">发信邮箱</label>
            <select
              v-model="editFromAccountId"
              class="w-full hygge-input rounded-xl px-3 py-2 text-xs"
            >
              <option v-for="acc in smtpEnabledAccounts" :key="acc.id" :value="acc.id">
                {{ acc.emailAddress }}
              </option>
            </select>
          </div>

          <!-- 收件人 -->
          <div class="space-y-1">
            <label class="block text-[10px] font-mono uppercase tracking-wider text-ink-400">收件人</label>
            <input
              v-model="editTo"
              type="text"
              class="w-full hygge-input rounded-xl px-3 py-2 text-xs"
              placeholder="收件人地址（逗号分隔）"
            />
          </div>

          <!-- 抄送/密送（可折叠） -->
          <div v-if="showCcBcc" class="space-y-2">
            <div class="space-y-1">
              <label class="block text-[10px] font-mono uppercase tracking-wider text-ink-400">抄送</label>
              <input
                v-model="editCc"
                type="text"
                class="w-full hygge-input rounded-xl px-3 py-2 text-xs"
                placeholder="抄送地址（逗号分隔）"
              />
            </div>
            <div class="space-y-1">
              <label class="block text-[10px] font-mono uppercase tracking-wider text-ink-400">密送</label>
              <input
                v-model="editBcc"
                type="text"
                class="w-full hygge-input rounded-xl px-3 py-2 text-xs"
                placeholder="密送地址（逗号分隔）"
              />
            </div>
          </div>
          <button
            v-else
            type="button"
            class="text-[10px] text-ink-400 hover:text-stone-grey transition"
            @click="showCcBcc = true"
          >
            + 添加抄送/密送
          </button>

          <!-- 主题 -->
          <div class="space-y-1">
            <label class="block text-[10px] font-mono uppercase tracking-wider text-ink-400">主题</label>
            <input
              v-model="editSubject"
              type="text"
              class="w-full hygge-input rounded-xl px-3 py-2 text-sm"
              placeholder="邮件主题"
            />
          </div>

          <!-- 正文（纯文本，禁 v-html） -->
          <div class="space-y-1">
            <label class="block text-[10px] font-mono uppercase tracking-wider text-ink-400">正文</label>
            <textarea
              v-model="editBody"
              class="w-full hygge-input rounded-xl px-3 py-2.5 text-sm min-h-[200px] resize-y leading-relaxed"
              placeholder="邮件正文…"
            />
          </div>

          <!-- AI 润色建议 -->
          <div
            v-if="polishedText"
            class="border border-border rounded-xl p-3 bg-light-beige/50 space-y-2"
          >
            <div class="flex items-center justify-between">
              <span class="text-[10px] font-mono text-warm-accent">AI 润色建议</span>
              <div class="flex items-center gap-2">
                <button
                  type="button"
                  class="text-xs text-sage hover:underline"
                  @click="adoptPolished"
                >
                  采用
                </button>
                <button
                  type="button"
                  class="text-xs text-ink-300 hover:text-stone-grey"
                  @click="cancelPolish"
                >
                  放弃
                </button>
              </div>
            </div>
            <p class="text-xs text-dark-stone whitespace-pre-wrap break-words leading-relaxed">
              {{ polishedText }}
            </p>
          </div>
        </div>

        <!-- 底部操作栏 -->
        <div class="px-5 py-3 border-t border-border flex items-center justify-between gap-2 shrink-0">
          <button
            type="button"
            class="flex items-center gap-1.5 px-3 py-2 rounded-xl text-xs text-stone-grey hover:bg-light-beige transition"
            :disabled="polishLoading || !editBody.trim()"
            @click="polishDraft"
          >
            <Spin v-if="polishLoading" size="text-xs" />
            <i v-else class="fa-solid fa-wand-magic-sparkles text-[10px]" />
            AI 润色
          </button>
          <button
            type="button"
            class="flex items-center gap-1.5 px-4 py-2 rounded-xl text-xs font-medium bg-dark-stone text-warm-white hover:bg-deep-charcoal transition disabled:opacity-50"
            :disabled="sending"
            @click="sendDraft"
          >
            <Spin v-if="sending" size="text-xs" />
            <span>{{ sending ? '发送中…' : '发送' }}</span>
          </button>
        </div>
      </template>
    </div>

    <!-- 新建草稿弹窗 -->
    <Modal v-model="showNewDraft" title="新建草稿">
      <p class="text-xs text-ink-500 mb-3">
        新建草稿需要选择发信邮箱。只有配置了 SMTP 的账号可选。
      </p>
      <div v-if="smtpEnabledAccounts.length === 0" class="text-xs text-danger-text">
        没有配置发信通道（SMTP）的邮箱账号。请先在设置中配置。
      </div>
      <select
        v-else
        v-model="newDraftAccountId"
        class="w-full hygge-input rounded-xl px-3 py-2 text-sm"
      >
        <option :value="null" disabled>选择发信邮箱…</option>
        <option v-for="acc in smtpEnabledAccounts" :key="acc.id" :value="acc.id">
          {{ acc.emailAddress }}
        </option>
      </select>
      <template #footer>
        <button
          type="button"
          class="px-4 py-2 rounded-xl text-xs font-medium text-stone-grey hover:bg-light-beige transition"
          @click="showNewDraft = false"
        >
          取消
        </button>
        <button
          type="button"
          class="px-4 py-2 rounded-xl text-xs font-medium bg-dark-stone text-warm-white hover:bg-deep-charcoal transition disabled:opacity-40 disabled:cursor-not-allowed"
          :disabled="newDraftAccountId == null"
          @click="createDraft"
        >
          创建
        </button>
      </template>
    </Modal>

    <!-- 删除草稿确认 -->
    <ConfirmDialog
      v-model="showDeleteConfirm"
      title="删除草稿"
      message="确定删除这份草稿吗？删除后不可恢复。"
      confirm-text="删除"
      danger
      @confirm="doDeleteDraft"
    />
  </div>
</template>
