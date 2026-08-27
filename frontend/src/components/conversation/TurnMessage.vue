<script setup lang="ts">
/**
 * 单轮对话消息（FRONTEND.md §3.5）。
 *
 * - 用户消息气泡（右）+ AI 回答气泡（左）+ 读取证据 + 提案卡片。
 * - **inContext:false 灰显但照常显示**（opacity-60，不隐藏）——这是本页最易做错处：
 *   "用户翻得到的历史" ≠ "模型拿得到的上下文"，藏起来用户会觉得记录丢了。
 * - failed/cancelled 灰显（cancelled 显示半截回答 + "已停止"标记），提供「重新提问」。
 * - 提案卡片用 ApprovalCard 共用组件（对话页与审批页同一渲染路径）。
 *   local_delete 卡片需要 targets，这里按 id 取详情（列表接口不给 targets）。
 * - 禁 v-html：AI 回答、证据、卡片正文全 {{ }} + whitespace-pre-wrap。
 */
import { ref, watch, computed } from 'vue'
import { api } from '@/api/client'
import type { Turn, ActionTarget, PendingActionDetail } from '@/utils/conversation'
import EvidenceList from './EvidenceList.vue'
import ApprovalCard from './ApprovalCard.vue'

const props = defineProps<{
  turn: Turn
}>()

const emit = defineEmits<{
  /** 重新提问（用原 userMessage 新建一轮，不恢复调用栈） */
  retry: [message: string]
  /** 批准/拒绝后通知父组件刷新 */
  decided: []
}>()

/** local_delete 卡片的 targets（按 id 取详情，列表接口不给 targets） */
const targetsMap = ref<Map<number, ActionTarget[]>>(new Map())
const targetsLoading = ref<Set<number>>(new Set())

/** 需要取 targets 的卡片（仅 local_delete）。
 *  actions 防御 null：后端对 failed 轮次可能返回 null 而非 []（契约缺口）。 */
const actions = computed(() => props.turn.actions ?? [])
const localDeleteActions = computed(() =>
  actions.value.filter((a) => a.actionType === 'local_delete'),
)

/** 加载 local_delete 卡片的 targets */
async function loadTargets(actionId: number): Promise<void> {
  if (targetsMap.value.has(actionId) || targetsLoading.value.has(actionId)) return
  targetsLoading.value.add(actionId)
  try {
    const { data, error } = await api.GET('/actions/{id}', {
      params: { path: { id: actionId } },
    })
    if (error || !data) return
    // PendingActionDetail 是 PendingActionCard & { targets }，取 targets
    const detail = data as PendingActionDetail
    targetsMap.value.set(actionId, detail.targets)
  } catch {
    // 加载失败静默（卡片仍显示 targetCount）
  } finally {
    targetsLoading.value.delete(actionId)
  }
}

// turn 变化时清空 targets 缓存
watch(
  () => props.turn.id,
  () => {
    targetsMap.value.clear()
    targetsLoading.value.clear()
    // 为 local_delete 卡片加载 targets
    for (const a of localDeleteActions.value) {
      void loadTargets(a.id)
    }
  },
  { immediate: true },
)

/** 是否灰显：inContext=false 或 status 非 completed */
const isGrayed = computed(() => !props.turn.inContext)

/** AI 回答文本（cancelled 是半截，completed 是完整，failed 为 null） */
const answerText = computed(() => props.turn.finalAnswer)
</script>

<template>
  <div
    class="space-y-2.5"
    :class="{ 'opacity-60': isGrayed }"
  >
    <!-- 用户消息（右） -->
    <div class="flex justify-end">
      <div class="max-w-[80%] px-3.5 py-2 rounded-2xl rounded-tr-sm bg-dark-stone text-warm-white text-sm whitespace-pre-wrap break-words">
        {{ turn.userMessage }}
      </div>
    </div>

    <!-- AI 回答（左） -->
    <div class="flex justify-start">
      <div class="max-w-[85%] space-y-2">
        <!-- 状态标记 -->
        <div v-if="turn.status === 'cancelled'" class="flex items-center gap-1.5 text-[10px] text-clay">
          <i class="fa-solid fa-hand text-[9px]" /> 已停止
        </div>
        <div v-if="turn.status === 'failed'" class="flex items-center gap-1.5 text-[10px] text-danger-text">
          <i class="fa-solid fa-circle-xmark text-[9px]" />
          {{ turn.failureReason ?? '生成失败' }}
        </div>

        <!-- 回答正文（纯文本，禁 v-html） -->
        <div
          v-if="answerText"
          class="px-3.5 py-2.5 rounded-2xl rounded-tl-sm bg-light-beige text-dark-stone text-sm whitespace-pre-wrap break-words leading-relaxed"
        >
          {{ answerText }}
        </div>

        <!-- cancelled 且无半截文字 -->
        <div v-else-if="turn.status === 'cancelled'" class="text-xs text-ink-400 italic">
          停止前未生成任何文字
        </div>

        <!-- 读取证据（防御 null） -->
        <EvidenceList :evidence="turn.evidence ?? []" />

        <!-- 提案卡片 -->
        <div v-if="actions.length > 0" class="space-y-2 pt-1">
          <ApprovalCard
            v-for="action in actions"
            :key="action.id"
            :card="action"
            :targets="targetsMap.get(action.id)"
            :targets-loading="action.actionType === 'local_delete' && targetsLoading.has(action.id)"
            @decided="emit('decided')"
          />
        </div>

        <!-- 重新提问按钮（failed/cancelled） -->
        <button
          v-if="turn.status === 'failed' || turn.status === 'cancelled'"
          type="button"
          class="text-[11px] text-stone-grey hover:text-deep-charcoal transition flex items-center gap-1"
          @click="emit('retry', turn.userMessage)"
        >
          <i class="fa-solid fa-rotate-right text-[9px]" /> 重新提问
        </button>
      </div>
    </div>
  </div>
</template>
