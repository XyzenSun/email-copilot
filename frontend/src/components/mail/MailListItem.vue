<script setup lang="ts">
/**
 * 邮件列表项。收件箱 / 已发送 / 搜索结果共用。
 *
 * 展示：checkbox（批量选择）、方向标（outbound 显示「发」）、
 * 发件人/收件人（outbound 显示收件人）、DKIM 对勾、时间、主题、snippet、分类、标签、附件。
 *
 * 选中态（当前在右栏打开的会话）：list-pick class（左侧 sage 竖条）+ bg-white。
 * 点击整行 → emit select（打开会话详情）；点击 checkbox → emit toggle-check（批量选择）。
 *
 * snippet 与所有文本字段一律 {{ }} 文本插值，**禁 v-html**（正文由攻击者控制）。
 */
import { computed } from 'vue'
import type { MessageSummary, Tag } from '@/utils/mail'
import { formatRecipients, CATEGORY_LABELS } from '@/utils/mail'
import { formatRelative } from '@/utils/format'
import { highlightSnippet, type SnippetSegment } from '@/utils/snippet'
import Chip from '@/components/ui/Chip.vue'

/**
 * 列表项与搜索结果共用。搜索结果复用本组件的两条扩展：
 *   - keywords：提供时 snippet 按词切分用 <mark> 渲染命中处（搜索结果加粗）；
 *     不提供则纯文本插值（收件箱/已发送列表）。禁 v-html——highlightSnippet
 *     产出的是结构（分段数组）而非 HTML 字符串，模板逐段渲染。
 *   - showCheck：搜索结果不做批量删除，传 false 隐藏勾选框；列表默认 true。
 */
const props = withDefaults(
  defineProps<{
    message: MessageSummary
    /** 是否为当前右栏打开的会话（高亮） */
    selected: boolean
    /** 是否被勾选（批量删除）；搜索结果不传默认 false */
    checked?: boolean
    allTags: Tag[]
    /** 是否显示批量选择 checkbox。搜索结果传 false 隐藏（无批量删除）。默认 true。 */
    showCheck?: boolean
    /** 高亮关键词：提供时 snippet 按词切分用 <mark> 渲染（搜索命中处加粗）。
     *  不提供则纯文本插值。禁 v-html——highlightSnippet 产出结构非 HTML 字符串。 */
    keywords?: string[]
  }>(),
  { showCheck: true, checked: false },
)

const emit = defineEmits<{
  select: [threadId: number, messageId: number]
  'toggle-check': [id: number]
}>()

const isOutbound = computed(() => props.message.direction === 'outbound')

/**
 * snippet 分段：keywords 提供时按词切分、命中处标 hit（模板渲染 <mark>），
 * 否则返回整段非命中（纯文本插值）。这是 snippet 加粗的唯一实现处，禁 v-html。
 */
const snippetSegments = computed<SnippetSegment[]>(() =>
  highlightSnippet(props.message.snippet, props.keywords ?? []),
)

/** 展示名：inbound 显示发件人，outbound 显示收件人（发件人恒为自己）。 */
const displayName = computed(() => {
  if (isOutbound.value) {
    return formatRecipients(props.message.recipients)
  }
  return props.message.fromDisplay || props.message.fromAddress
})

/** 把 tag IDs 映射为展示名（allTags 里查） */
const tagLabels = computed(() => {
  const map = new Map(props.allTags.map((t) => [t.id, t.displayName]))
  return props.message.tags
    .map((id) => map.get(id))
    .filter((x): x is string => x != null)
})

function onSelect(): void {
  emit('select', props.message.threadId, props.message.id)
}
</script>

<template>
  <div
    :class="[
      'relative flex items-start gap-2.5 px-3.5 py-3 cursor-pointer transition border-b border-border-soft',
      selected ? 'list-pick bg-white shadow-sm' : 'hover:bg-light-beige/50',
    ]"
    @click="onSelect"
  >
    <!-- 批量选择 checkbox：仅列表显示（showCheck）；搜索结果隐藏。点击不触发行选择（stop） -->
    <input
      v-if="showCheck"
      type="checkbox"
      :checked="checked"
      class="mt-1 shrink-0 accent-stone-grey cursor-pointer"
      @click.stop="emit('toggle-check', message.id)"
    />

    <div class="flex-1 min-w-0">
      <!-- 第一行：发件人/收件人 + 时间 -->
      <div class="flex items-center justify-between gap-2">
        <div class="flex items-center gap-1.5 min-w-0">
          <span class="font-serif font-semibold text-sm text-deep-charcoal truncate">
            {{ displayName }}
          </span>
          <!-- outbound 方向标 -->
          <span v-if="isOutbound" class="text-[9px] font-mono text-sage shrink-0">发</span>
          <!-- DKIM 对勾：仅 inbound 且 dkimPassed=true 显示 -->
          <i
            v-if="message.dkimPassed === true"
            class="fa-solid fa-check text-[10px] text-sage shrink-0"
            title="DKIM 验证通过"
          />
        </div>
        <span class="text-[10px] text-ink-400 shrink-0">{{ formatRelative(message.receivedAt) }}</span>
      </div>

      <!-- 第二行：主题 -->
      <p class="text-xs text-dark-stone truncate mt-0.5">
        {{ message.subject || '(无主题)' }}
      </p>

      <!-- 第三行：snippet。keywords 提供时按词切分用 <mark> 加粗命中处（搜索结果），
           否则纯文本插值（列表）。禁 v-html——highlightSnippet 产出结构而非 HTML 字符串，
           模板逐段渲染，邮件正文/snippet 完全由攻击者控制，不可信任为 HTML -->
      <p v-if="keywords" class="text-[11px] text-ink-500 line-clamp-2 mt-1">
        <template v-for="(seg, i) in snippetSegments" :key="i">
          <mark v-if="seg.hit" class="bg-warn-bg text-warn-text rounded-sm px-0.5">{{
            seg.text
          }}</mark>
          <span v-else>{{ seg.text }}</span>
        </template>
      </p>
      <p v-else class="text-[11px] text-ink-500 line-clamp-2 mt-1">{{ message.snippet }}</p>

      <!-- 第四行：分类 + 标签 + 附件。
           inbound 且 category 为 null（AI 未处理/未配置）显示「未分类」灰色 chip，
           让用户一眼看出这封还没被分类。outbound 不进流水线、category 恒为 null，不显示。 -->
      <div class="flex items-center gap-1.5 mt-1.5 flex-wrap">
        <Chip v-if="message.category" variant="category" :category="message.category">
          {{ CATEGORY_LABELS[message.category] }}
        </Chip>
        <Chip v-else-if="!isOutbound" variant="status" class="bg-light-beige text-ink-300 border-border-soft">
          未分类
        </Chip>
        <Chip v-for="label in tagLabels" :key="label" variant="tag">{{ label }}</Chip>
        <i
          v-if="message.hasAttachment"
          class="fa-solid fa-paperclip text-[10px] text-ink-400"
          title="含附件"
        />
      </div>
    </div>
  </div>
</template>
