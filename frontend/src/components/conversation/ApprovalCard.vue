<script setup lang="ts">
/**
 * 审批卡片（共用组件）——对话页与审批页同一渲染路径（FRONTEND.md §3.6）。
 *
 * **零件渲染，绝不取 AI 自述文本**（硬约束 1）：按 actionType 分支模板，
 * 从 actionType/targets/content 快照渲染。这是用户做安全决策的依据——
 * 若概述由 AI 生成，注入内容可把"删除全部邮件"写成"加标签"。
 *
 * **批准 200+execution.status 不进 error 分支**（硬约束 2）：
 * approve 返回 200，读 execution.status 分支（executing/succeeded/failed/indeterminate）；
 * failed/indeterminate 显示 resultMessage，**不重试**（批准一次一用、已被消费）；
 * 重复批准 409 → toast 提示。
 */
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api/client'
import { isProblem, onCode } from '@/api/errors'
import { useUiStore } from '@/stores/ui'
import { formatDateTime, formatRelative } from '@/utils/format'
import { formatRecipients } from '@/utils/mail'
import {
  ACTION_TYPE_LABELS,
  APPROVAL_STATUS_META,
  type PendingActionCard,
  type ActionTarget,
  type ApprovalResult,
} from '@/utils/conversation'
import { resolveExecutionDisplay, shouldShowActionButtons } from '@/utils/approval'
import Spin from '@/components/ui/Spin.vue'

const props = defineProps<{
  card: PendingActionCard
  /** local_delete 的目标列表（列表接口不给，详情接口才有） */
  targets?: ActionTarget[]
  /** 是否正在加载 targets（local_delete 且 targets 为空时显示加载态） */
  targetsLoading?: boolean
}>()

const emit = defineEmits<{
  /** 批准/拒绝后通知父组件刷新（角标、列表等） */
  decided: []
}>()

const router = useRouter()
const ui = useUiStore()

// ── 本地卡片状态（approve/reject 后就地更新，无需父组件重拉） ──
const localStatus = ref(props.card.approvalStatus)
const localExecution = ref(props.card.execution)
const localCancelReason = ref(props.card.cancelReason)
const localDecidedAt = ref(props.card.decidedAt)

// props 变化时同步（父组件重拉列表后用新 card 覆盖）
watch(
  () => props.card,
  (c) => {
    localStatus.value = c.approvalStatus
    localExecution.value = c.execution
    localCancelReason.value = c.cancelReason
    localDecidedAt.value = c.decidedAt
  },
)

// ── 按钮状态 ──
const approving = ref(false)
const rejecting = ref(false)

// ── local_delete 目标列表展开 ──
const targetsExpanded = ref(false)
/** 默认展开前 3 条，其余收起 */
const visibleTargets = computed(() => {
  if (!props.targets) return []
  return targetsExpanded.value ? props.targets : props.targets.slice(0, 3)
})
const hiddenTargetCount = computed(() => {
  if (!props.targets) return 0
  return Math.max(0, props.targets.length - 3)
})

// ── 展示状态派生 ──
const showButtons = computed(() => shouldShowActionButtons(localStatus.value))
const execDisplay = computed(() => resolveExecutionDisplay(localExecution.value))
const statusMeta = computed(() => APPROVAL_STATUS_META[localStatus.value])
const isGrayed = computed(() => statusMeta.value.grayed)

/** 到期时间倒计文本 */
const expiryText = computed(() => formatDateTime(props.card.expiresAt))

// ── 批准 ──
async function approve(): Promise<void> {
  if (approving.value || rejecting.value) return
  approving.value = true
  try {
    // POST /actions/{id}/approve（无 body）→ 200 ApprovalResult
    // **200+execution.status：failed/indeterminate 仍是 200，不进 error 分支、不重试**
    const { data, error } = await api.POST('/actions/{id}/approve', {
      params: { path: { id: props.card.id } },
    })
    if (error || !data) return // 不应发生（200 时 error 为 undefined）
    // 就地更新卡片状态
    applyResult(data)
    ui.pushToast(execDisplay.value?.tone === 'danger' ? 'error' : 'success', getApproveToast(data))
    emit('decided')
    void ui.refreshPendingBadge()
  } catch (e) {
    // 409 PENDING_ACTION_ALREADY_DECIDED / PENDING_ACTION_EXPIRED → 提示，不重试
    if (isProblem(e)) {
      const msg = onCode(
        e,
        {
          PENDING_ACTION_ALREADY_DECIDED: () => '该提案已被决定，无法重复操作',
          PENDING_ACTION_EXPIRED: () => '该提案已过期，请让 AI 重新生成',
        },
        () => '审批失败',
      ) ?? '审批失败'
      ui.pushToast('error', msg)
    } else {
      ui.pushToast('error', '审批失败，请稍后再试')
    }
  } finally {
    approving.value = false
  }
}

/** 从 ApprovalResult 更新本地卡片状态 */
function applyResult(result: ApprovalResult): void {
  localStatus.value = result.approvalStatus
  localExecution.value = result.execution
  localDecidedAt.value = result.decidedAt
}

/** 批准后的 toast 文案（据执行终态分支） */
function getApproveToast(result: ApprovalResult): string {
  const exec = result.execution
  if (!exec) return '已批准'
  switch (exec.status) {
    case 'executing':
      return '已批准，正在执行…'
    case 'succeeded':
      return '已批准并执行成功'
    case 'failed':
      return exec.resultMessage ?? '执行失败'
    case 'indeterminate':
      return '结果不确定，邮件可能已发出，请去收件方确认'
  }
}

// ── 拒绝 ──
async function reject(): Promise<void> {
  if (approving.value || rejecting.value) return
  rejecting.value = true
  try {
    const { data, error } = await api.POST('/actions/{id}/reject', {
      params: { path: { id: props.card.id } },
    })
    if (error || !data) return
    applyResult(data)
    ui.pushToast('info', '已拒绝')
    emit('decided')
    void ui.refreshPendingBadge()
  } catch (e) {
    if (isProblem(e)) {
      const msg = onCode(
        e,
        {
          PENDING_ACTION_ALREADY_DECIDED: () => '该提案已被决定，无法重复操作',
          PENDING_ACTION_EXPIRED: () => '该提案已过期',
        },
        () => '操作失败',
      ) ?? '操作失败'
      ui.pushToast('error', msg)
    } else {
      ui.pushToast('error', '操作失败，请稍后再试')
    }
  } finally {
    rejecting.value = false
  }
}

/** 跳到来源对话（conversationId 为 null 时不跳） */
function gotoConversation(): void {
  if (props.card.conversationId == null) return
  void router.push(`/conversations/${props.card.conversationId}`)
}
</script>

<template>
  <div
    class="rounded-2xl border bg-white hygge-card overflow-hidden"
    :class="isGrayed ? 'border-border opacity-60' : 'border-border'"
  >
    <!-- 顶部：类型标签 + 来源对话 + 到期时间 -->
    <div class="px-4 py-2.5 border-b border-border-soft flex items-center justify-between gap-2">
      <div class="flex items-center gap-2 min-w-0">
        <span class="text-[10px] font-mono font-bold uppercase tracking-wider text-deep-charcoal shrink-0">
          {{ ACTION_TYPE_LABELS[card.actionType] }}
        </span>
        <!-- 来源对话链接 -->
        <button
          v-if="card.conversationId != null"
          type="button"
          class="text-[10px] text-ink-400 hover:text-stone-grey transition truncate"
          @click="gotoConversation"
        >
          <i class="fa-solid fa-link text-[8px] mr-1" />来源对话
        </button>
        <span v-else class="text-[10px] text-ink-300 truncate">来源对话已删除</span>
      </div>
      <!-- 到期时间（仅 pending 显示倒计，其余显示决定时间） -->
      <span v-if="localStatus === 'pending'" class="text-[10px] text-clay flex items-center gap-1 shrink-0">
        <i class="fa-regular fa-clock text-[9px]" />
        {{ expiryText }} 到期
      </span>
      <span v-else-if="localDecidedAt" class="text-[10px] text-ink-400 shrink-0">
        {{ formatDateTime(localDecidedAt) }}
      </span>
    </div>

    <!-- 零件渲染区（按 actionType 分支） -->
    <div class="px-4 py-3 space-y-2.5">
      <!-- send_email / save_draft：正文直展 + 收件人 + 主题 -->
      <template v-if="card.actionType !== 'local_delete' && card.content">
        <!-- 主题 -->
        <div class="flex items-start gap-2">
          <span class="text-[10px] font-mono text-ink-400 shrink-0 mt-0.5">主题</span>
          <span class="text-xs text-dark-stone font-medium break-words">{{ card.content.subject }}</span>
        </div>
        <!-- 发件账号 -->
        <div class="flex items-start gap-2">
          <span class="text-[10px] font-mono text-ink-400 shrink-0 mt-0.5">发件</span>
          <span class="text-xs text-stone-grey break-all">{{ card.content.fromAddress }}</span>
        </div>
        <!-- 收件人 -->
        <div class="flex items-start gap-2">
          <span class="text-[10px] font-mono text-ink-400 shrink-0 mt-0.5">收件</span>
          <span class="text-xs text-stone-grey break-all">
            {{ formatRecipients(card.content.recipients) }}
          </span>
        </div>
        <!-- 回复哪封（若有） -->
        <div v-if="card.content.inReplyToSubject" class="flex items-start gap-2">
          <span class="text-[10px] font-mono text-ink-400 shrink-0 mt-0.5">回复</span>
          <span class="text-xs text-ink-500 italic break-words">《{{ card.content.inReplyToSubject }}》</span>
        </div>
        <!-- 正文（直接展开，不藏「查看详情」后——批准前必须看见正文写了什么） -->
        <div class="mt-1">
          <span class="text-[10px] font-mono text-ink-400">正文</span>
          <pre class="mt-1 text-xs text-dark-stone whitespace-pre-wrap break-words leading-relaxed font-sans bg-light-beige/50 rounded-xl p-3">{{ card.content.bodyText }}</pre>
        </div>
      </template>

      <!-- local_delete：红色警告头 + 目标列表 -->
      <template v-else-if="card.actionType === 'local_delete'">
        <!-- 醒目提示：将删除 N 封邮件 -->
        <div class="flex items-center gap-2 px-3 py-2 rounded-xl bg-danger-bg border border-danger-border">
          <i class="fa-solid fa-triangle-exclamation text-danger-text" />
          <span class="text-xs font-semibold text-danger-text">
            将删除 {{ card.targetCount }} 封邮件
          </span>
        </div>
        <!-- 目标列表：默认展开前 3 + 「还有 N 封」 -->
        <div v-if="targets && targets.length > 0" class="space-y-1">
          <div
            v-for="t in visibleTargets"
            :key="t.messageId"
            class="flex items-center gap-2 px-2.5 py-1.5 rounded-lg bg-light-beige/40 text-xs"
          >
            <span class="text-ink-500 truncate flex-1">{{ t.subject ?? '(无主题)' }}</span>
            <span class="text-[10px] text-ink-400 shrink-0 truncate max-w-[120px]">{{ t.fromAddress }}</span>
            <span class="text-[10px] text-ink-300 shrink-0">{{ formatRelative(t.receivedAt) }}</span>
          </div>
          <!-- 还有 N 封：可展开 -->
          <button
            v-if="hiddenTargetCount > 0 && !targetsExpanded"
            type="button"
            class="w-full text-center text-[11px] text-stone-grey hover:text-deep-charcoal hover:bg-light-beige/50 rounded-lg py-1.5 transition"
            @click="targetsExpanded = true"
          >
            还有 {{ hiddenTargetCount }} 封，展开查看
          </button>
          <button
            v-else-if="targetsExpanded && props.targets && props.targets.length > 3"
            type="button"
            class="w-full text-center text-[11px] text-stone-grey hover:text-deep-charcoal hover:bg-light-beige/50 rounded-lg py-1.5 transition"
            @click="targetsExpanded = false"
          >
            收起
          </button>
        </div>
        <!-- targets 加载中 -->
        <div v-else-if="targetsLoading" class="flex items-center gap-2 text-xs text-ink-400 py-2">
          <Spin size="text-xs" /> 加载目标列表…
        </div>
      </template>
    </div>

    <!-- 执行终态区（approved 后显示） -->
    <div v-if="execDisplay" class="px-4 py-2.5 border-t border-border-soft space-y-1">
      <div class="flex items-center gap-2">
        <Spin v-if="execDisplay.loading" size="text-xs" />
        <i
          v-else
          class="fa-solid text-[10px]"
          :class="{
            'fa-circle-check text-sage': execDisplay.tone === 'sage',
            'fa-circle-xmark text-danger-text': execDisplay.tone === 'danger',
            'fa-circle-question text-clay': execDisplay.tone === 'clay',
            'fa-circle-info text-ink-400': execDisplay.tone === 'ink',
          }"
        />
        <span
          class="text-xs font-medium"
          :class="{
            'text-sage': execDisplay.tone === 'sage',
            'text-danger-text': execDisplay.tone === 'danger',
            'text-clay': execDisplay.tone === 'clay',
            'text-ink-500': execDisplay.tone === 'ink',
          }"
        >
          {{ execDisplay.label }}
        </span>
      </div>
      <!-- resultMessage（failed/indeterminate 时展示） -->
      <p v-if="execDisplay.resultMessage" class="text-[11px] text-ink-500 break-words pl-5">
        {{ execDisplay.resultMessage }}
      </p>
    </div>

    <!-- cancelled 取消原因 -->
    <div v-if="localStatus === 'cancelled' && localCancelReason" class="px-4 py-2 border-t border-border-soft">
      <p class="text-[11px] text-ink-400">
        <i class="fa-solid fa-circle-info text-[9px] mr-1" />{{ localCancelReason }}
      </p>
    </div>

    <!-- 状态徽标（非 pending） -->
    <div v-if="!showButtons && !execDisplay" class="px-4 py-2 border-t border-border-soft">
      <span
        class="text-[10px] font-mono uppercase tracking-wider"
        :class="{
          'text-sage': statusMeta.tone === 'sage',
          'text-clay': statusMeta.tone === 'clay',
          'text-ink-400': statusMeta.tone === 'ink',
        }"
      >
        {{ statusMeta.label }}
      </span>
    </div>

    <!-- 批准/拒绝按钮（仅 pending 显示，不带 body） -->
    <div v-if="showButtons" class="px-4 py-2.5 border-t border-border-soft flex items-center justify-end gap-2">
      <button
        type="button"
        class="px-3 py-1.5 rounded-xl text-xs font-medium text-stone-grey border border-border hover:bg-light-beige transition disabled:opacity-50"
        :disabled="rejecting"
        @click="reject"
      >
        <Spin v-if="rejecting" size="text-[10px]" />
        {{ rejecting ? '' : '拒绝' }}
      </button>
      <button
        type="button"
        class="flex items-center gap-1.5 px-4 py-1.5 rounded-xl text-xs font-medium bg-sage text-white hover:brightness-110 transition disabled:opacity-50"
        :disabled="approving"
        @click="approve"
      >
        <Spin v-if="approving" size="text-[10px]" />
        <span>{{ approving ? '执行中…' : '批准' }}</span>
      </button>
    </div>
  </div>
</template>
