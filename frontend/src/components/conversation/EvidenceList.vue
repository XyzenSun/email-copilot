<script setup lang="ts">
/**
 * 读取证据列表（FRONTEND.md §3.5 TurnReadEvidence）。
 *
 * 展示本轮 AI 实际读取了哪些邮件/会话，**每条可点击跳到原文**
 * （这是发现间接注入的唯一途径）。
 *
 * - subject 为 null 表示那封邮件已被删除 → 显示「该邮件已删除」不可点击。
 * - 证据由代码写入（event:evidence），绝不取 AI 自述文本。
 * - 跳原文：message 类型用 msg 定位、thread 类型用 thread 定位。
 *   message 类型需要先 GET /messages/{id} 拿 threadId（证据只给 messageId）。
 *
 * evidence 为空时不渲染（不占位）。
 */
import { useRouter } from 'vue-router'
import { api } from '@/api/client'
import { useUiStore } from '@/stores/ui'
import { formatRelative } from '@/utils/format'
import { EVIDENCE_SOURCE_LABELS, type ReadEvidence } from '@/utils/conversation'

const props = defineProps<{
  evidence: ReadEvidence[]
}>()

const router = useRouter()
const ui = useUiStore()

/** 点击证据跳到原文 */
async function jumpToEvidence(ev: ReadEvidence): Promise<void> {
  // subject 为 null = 邮件已删除，不可点击
  if (ev.subject == null) return

  if (ev.targetType === 'thread') {
    // thread 类型：targetId 即 threadId
    await router.push({ path: '/', query: { thread: String(ev.targetId) } })
    return
  }

  // message 类型：targetId 是 messageId，需解析出 threadId 才能定位会话视图
  try {
    const { data, error } = await api.GET('/messages/{id}', {
      params: { path: { id: ev.targetId } },
    })
    if (error || !data) {
      ui.pushToast('error', '无法定位该邮件')
      return
    }
    await router.push({
      path: '/',
      query: { thread: String(data.threadId), msg: String(ev.targetId) },
    })
  } catch {
    ui.pushToast('error', '该邮件可能已被删除')
  }
}
</script>

<template>
  <div v-if="evidence.length > 0" class="mt-2 space-y-1">
    <div class="flex items-center gap-1.5 text-[10px] font-mono uppercase tracking-wider text-ink-300">
      <i class="fa-solid fa-magnifying-glass text-[9px]" />
      读取证据（{{ evidence.length }}）
    </div>
    <div class="space-y-1">
      <component
        :is="ev.subject == null ? 'div' : 'button'"
        v-for="(ev, idx) in evidence"
        :key="idx"
        type="button"
        :class="[
          'w-full flex items-center gap-2 px-2.5 py-1.5 rounded-lg text-left transition text-xs',
          ev.subject == null
            ? 'text-ink-300 cursor-default'
            : 'text-stone-grey hover:bg-light-beige hover:text-deep-charcoal',
        ]"
        :disabled="ev.subject == null"
        @click="ev.subject != null && jumpToEvidence(ev)"
      >
        <!-- 来源小标签 -->
        <span
          class="text-[9px] font-mono px-1.5 py-0.5 rounded shrink-0"
          :class="ev.source === 'direct_read' ? 'bg-sage/15 text-sage' : 'bg-warm-accent/15 text-warm-accent'"
        >
          {{ EVIDENCE_SOURCE_LABELS[ev.source] }}
        </span>
        <span v-if="ev.subject != null" class="truncate flex-1">{{ ev.subject }}</span>
        <span v-else class="truncate flex-1 italic">该邮件已删除</span>
        <span v-if="ev.fromAddress != null" class="text-[10px] text-ink-400 shrink-0 truncate max-w-[140px]">
          {{ ev.fromAddress }}
        </span>
        <span v-if="ev.receivedAt != null" class="text-[10px] text-ink-300 shrink-0">
          {{ formatRelative(ev.receivedAt) }}
        </span>
      </component>
    </div>
  </div>
</template>
