<script setup lang="ts">
/**
 * 上下文用量条（FRONTEND.md §3.5）。展示 usedTokens/limitTokens + 百分比。
 *
 * - limitTokens 从接口取，不写死——用户可能换了窗口不同的模型。
 * - 超 80% 变警示色（clay）。
 * - compactedTurnCount > 0 时显示「早前 N 轮已压缩成摘要」。
 * - 清除上下文按钮：POST /conversations/{id}/context/clear，二次确认。
 */
import { computed } from 'vue'
import { formatTokenUsage } from '@/utils/format'
import type { ConversationContext } from '@/utils/conversation'

const props = defineProps<{
  context: ConversationContext
  /** 是否有轮次正在跑（跑时禁用清除按钮） */
  disabled?: boolean
}>()

const emit = defineEmits<{ clear: [] }>()

const usageText = computed(() =>
  formatTokenUsage(props.context.usedTokens, props.context.limitTokens),
)

/** 百分比，用于进度条宽度 + 警示色判定 */
const percent = computed(() => {
  const limit = props.context.limitTokens
  if (!limit || limit <= 0) return 0
  return Math.min(100, Math.round((props.context.usedTokens / limit) * 100))
})

/** 超 80% 变警示色 */
const isWarning = computed(() => percent.value >= 80)
</script>

<template>
  <div class="px-5 py-2.5 border-b border-border bg-light-beige/50 shrink-0 space-y-1.5">
    <div class="flex items-center justify-between gap-3">
      <div class="flex items-center gap-2 min-w-0">
        <i class="fa-solid fa-brain text-[10px] text-ink-400 shrink-0" />
        <span class="text-[10px] font-mono uppercase tracking-wider text-ink-400 shrink-0">
          上下文
        </span>
        <span
          class="text-[11px] font-mono"
          :class="isWarning ? 'text-clay font-semibold' : 'text-ink-500'"
        >
          {{ usageText }}
        </span>
      </div>
      <!-- 压缩痕迹 -->
      <span
        v-if="context.compactedTurnCount > 0"
        class="text-[10px] text-warm-accent flex items-center gap-1 shrink-0"
      >
        <i class="fa-solid fa-compress text-[9px]" />
        早前 {{ context.compactedTurnCount }} 轮已压缩成摘要
      </span>
    </div>

    <!-- 进度条 -->
    <div class="h-1 rounded-full bg-border overflow-hidden">
      <div
        class="h-full rounded-full transition-all duration-300"
        :class="isWarning ? 'bg-clay' : 'bg-sage'"
        :style="{ width: percent + '%' }"
      />
    </div>

    <!-- 清除上下文按钮 -->
    <div class="flex items-center justify-end">
      <button
        type="button"
        class="text-[10px] text-ink-400 hover:text-clay transition disabled:opacity-40 disabled:cursor-not-allowed"
        :disabled="disabled"
        @click="emit('clear')"
      >
        <i class="fa-solid fa-eraser text-[9px] mr-1" />清除上下文
      </button>
    </div>
  </div>
</template>
