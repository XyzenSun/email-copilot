<script setup lang="ts">
/**
 * 会话详情里的单封邮件气泡。ThreadView 里每封邮件用一个 MessageBubble 渲染。
 *
 * 收起态：发件人/收件人、主题、时间、方向标、分类、标签、附件标。
 * 展开态：正文 + 简中译文 + 附件元数据（无下载）+ 认证状态 + TagEditor + 回复/删除按钮。
 *
 * 方向区分（FRONTEND.md §3.3）：
 *   - inbound 靠左白底气泡，rounded-tl-sm
 *   - outbound 靠右 light-beige 气泡，rounded-tr-sm
 *   收到的与自己发出的都在会话里，据 direction 决定气泡方向——
 *   否则整段往来只剩对方发言，看起来像自言自语。
 *
 * 正文一律 {{ }} 文本插值，**禁 v-html**（正文完全由攻击者控制）。
 */
import { computed, ref } from 'vue'
import type { MessageSummary, MessageDetail, Tag, ReprocessStage } from '@/utils/mail'
import { formatRecipients, formatFileSize, CATEGORY_LABELS } from '@/utils/mail'
import { formatDateTime } from '@/utils/format'
import Chip from '@/components/ui/Chip.vue'
import Spin from '@/components/ui/Spin.vue'
import TagEditor from './TagEditor.vue'

const props = defineProps<{
  /** 可能是 MessageSummary（收起态）或 MessageDetail（展开后按需加载） */
  message: MessageSummary | MessageDetail
  expanded: boolean
  isHighlighted: boolean
  allTags: Tag[]
  /** 该封正在手动重新处理中（请求在飞），用于禁用按钮 + 转圈（ThreadView 跟踪） */
  reprocessing?: boolean
}>()

const emit = defineEmits<{
  'toggle-expand': []
  reply: []
  delete: []
  'update-tags': [tagIds: number[]]
  /** 用户触发手动重新处理某一步（仅 inbound；ThreadView 调 POST /messages/{id}/reprocess） */
  'reprocess': [stage: ReprocessStage]
}>()

/** 重新处理的阶段选择，默认翻译（最常见场景：全局关翻译后对单封英文信手动翻译） */
const reprocessStage = ref<ReprocessStage>('translation')

const isOutbound = computed(() => props.message.direction === 'outbound')

/** 是否已加载详情（MessageDetail 有 bodyText 字段，MessageSummary 没有） */
const hasDetail = computed(() => 'bodyText' in props.message)

const detail = computed(() => (hasDetail.value ? (props.message as MessageDetail) : null))

/**
 * 发件人/收件人标注行（按 Xyzen 2026-08-25 要求加标注）：
 *   - inbound：发件人: $名称 (from: $email)；名称为空时用 email 顶替
 *   - outbound：收件人: $收件人列表（发出的信发件人恒为自己，标注收件人更有意义）
 */
const partyLabel = computed(() => {
  if (isOutbound.value) {
    return { prefix: '收件人', value: formatRecipients(props.message.recipients) }
  }
  const name = props.message.fromDisplay || props.message.fromAddress
  return { prefix: '发件人', value: `${name} (from: ${props.message.fromAddress})` }
})

/** 认证状态展示文本 */
const authLabel = computed(() => {
  const domain = detail.value?.fromAuthenticatedDomain
  if (domain) return `已认证 · ${domain}`
  return '未认证'
})
</script>

<template>
  <div
    :data-msg-id="message.id"
    :class="[
      'flex flex-col rounded-2xl border transition',
      isOutbound ? 'ml-8 bg-light-beige border-border' : 'mr-8 bg-white border-border',
      isOutbound ? 'rounded-tr-sm' : 'rounded-tl-sm',
      isHighlighted ? 'ring-2 ring-sage/40' : '',
    ]"
  >
    <!-- 头部（点击展开/收起） -->
    <div
      class="flex items-start justify-between gap-2 px-4 py-3 cursor-pointer"
      @click="emit('toggle-expand')"
    >
      <div class="min-w-0 flex-1">
        <!-- 发件人/收件人标注行：发件人: $名称 (from: $email) / 收件人: $收件人 -->
        <div class="flex items-center gap-1.5">
          <span class="text-[10px] font-mono text-ink-300 shrink-0">{{ partyLabel.prefix }}</span>
          <span class="font-serif font-semibold text-sm text-deep-charcoal truncate">
            {{ partyLabel.value }}
          </span>
          <span v-if="isOutbound" class="text-[9px] font-mono text-sage shrink-0">发</span>
          <i
            v-if="message.dkimPassed === true"
            class="fa-solid fa-check text-[10px] text-sage shrink-0"
            title="DKIM 验证通过"
          />
        </div>
        <!-- 标题：加标注 + 加大加粗（font-serif font-semibold text-base） -->
        <p class="font-serif font-semibold text-base text-deep-charcoal truncate mt-1">
          <span class="text-[10px] font-mono text-ink-300 font-normal mr-1">标题</span>
          {{ message.subject || '(无主题)' }}
        </p>
      </div>
      <div class="flex items-center gap-2 shrink-0">
        <span class="text-[10px] text-ink-400">{{ formatDateTime(message.receivedAt) }}</span>
        <i
          :class="expanded ? 'fa-solid fa-chevron-up' : 'fa-solid fa-chevron-down'"
          class="text-[10px] text-ink-300"
        />
      </div>
    </div>

    <!-- 展开内容 -->
    <div v-if="expanded" class="px-4 pb-3 space-y-3 border-t border-border-soft pt-3">
      <!-- 详情未加载（ThreadView 正在按需 GET /messages/{id}） -->
      <div v-if="!hasDetail" class="flex items-center justify-center py-4">
        <span class="text-xs text-ink-400">加载中…</span>
      </div>

      <template v-else>
        <!-- 三段固定结构：摘要 / 原文 / 译文（译文仅流水线产出时显示，中文邮件不翻译故为空） -->
        <section v-if="detail?.summary" class="space-y-1">
          <p class="text-[10px] font-mono text-ink-300">摘要</p>
          <p class="text-sm text-dark-stone whitespace-pre-wrap break-words leading-relaxed">
            {{ detail.summary }}
          </p>
        </section>

        <section class="space-y-1">
          <p class="text-[10px] font-mono text-ink-300">原文</p>
          <div
            v-if="detail?.bodyText"
            class="text-sm text-dark-stone whitespace-pre-wrap break-words leading-relaxed"
          >
            {{ detail.bodyText }}
          </div>
          <p v-else class="text-xs text-ink-400 italic">
            （正文已清理或为空）
          </p>
        </section>

        <section v-if="detail?.translatedBody" class="space-y-1">
          <p class="text-[10px] font-mono text-ink-300">译文</p>
          <p class="text-sm text-dark-stone whitespace-pre-wrap break-words leading-relaxed">
            {{ detail.translatedBody }}
          </p>
        </section>

        <!-- 发件人认证状态（仅 inbound；outbound 不校验自己发的信） -->
        <div v-if="!isOutbound" class="flex items-center gap-1.5 text-[10px]">
          <i
            :class="detail?.fromAuthenticatedDomain ? 'fa-solid fa-shield-halved text-sage' : 'fa-solid fa-shield-halved text-ink-300'"
          />
          <span :class="detail?.fromAuthenticatedDomain ? 'text-sage' : 'text-ink-400'">
            {{ authLabel }}
          </span>
        </div>

        <!-- 附件元数据（无下载接口，只展示文件名+大小） -->
        <div v-if="detail?.attachments && detail.attachments.length > 0" class="space-y-1">
          <div
            v-for="att in detail.attachments"
            :key="att.id"
            class="flex items-center gap-2 text-xs text-ink-500"
          >
            <i class="fa-solid fa-paperclip text-[10px]" />
            <span class="truncate">{{ att.filename }}</span>
            <span class="text-ink-300 shrink-0">({{ formatFileSize(att.sizeBytes) }})</span>
          </div>
        </div>

        <!-- 分类 -->
        <div v-if="message.category" class="flex items-center gap-2">
          <Chip variant="category" :category="message.category">
            {{ CATEGORY_LABELS[message.category] }}
          </Chip>
        </div>

        <!-- 标签编辑器 + 操作按钮 -->
        <div class="flex items-center justify-between gap-2 pt-1">
          <TagEditor
            :message-id="message.id"
            :message-tags="message.tags"
            :all-tags="allTags"
            @update="(ids) => emit('update-tags', ids)"
          />
          <div class="flex items-center gap-3">
            <!-- 手动重新处理（仅 inbound；outbound 不进流水线，design §2.1） -->
            <div v-if="!isOutbound" class="flex items-center gap-1">
              <select
                v-model="reprocessStage"
                class="text-xs border border-border-soft rounded px-1 py-0.5 bg-white max-w-[7rem]"
                :disabled="reprocessing"
                title="选择要对这封邮件手动执行的流水线步骤"
              >
                <option value="translation">翻译</option>
                <option value="summary">摘要</option>
                <option value="classification">分类与标签</option>
                <option value="spam_judgment">垃圾评分</option>
              </select>
              <button
                type="button"
                class="flex items-center gap-1 text-xs text-ink-300 hover:text-deep-charcoal transition disabled:opacity-50"
                :disabled="reprocessing"
                @click.stop="emit('reprocess', reprocessStage)"
              >
                <Spin v-if="reprocessing" size="text-[10px]" />
                <i v-else class="fa-solid fa-arrows-rotate text-[10px]" />
                {{ reprocessing ? '处理中…' : '重新处理' }}
              </button>
            </div>
            <button
              type="button"
              class="flex items-center gap-1 text-xs text-stone-grey hover:text-deep-charcoal transition"
              @click.stop="emit('reply')"
            >
              <i class="fa-solid fa-reply text-[10px]" /> 回复
            </button>
            <button
              type="button"
              class="flex items-center gap-1 text-xs text-ink-300 hover:text-danger-text transition"
              @click.stop="emit('delete')"
            >
              <i class="fa-solid fa-trash text-[10px]" /> 删除
            </button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>
