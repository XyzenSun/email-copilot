<script setup lang="ts">
/**
 * 标签编辑器。会话详情页里手动增删邮件标签的唯一入口
 * （对话 AI 不持有标签工具——这是纠正流水线 AI 标注的唯一途径）。
 *
 * 下拉多选已有标签，点击即时 PUT /messages/{id}/tags 全量提交 tagIds。
 * 语义是**全量替换**（PUT）：取消勾选必须生效。
 * 分类不是标签，不混在这里。
 *
 * 用 @vueuse/core 的 onClickOutside 处理点击外部关闭下拉。
 */
import { ref } from 'vue'
import { onClickOutside } from '@vueuse/core'
import { api } from '@/api/client'
import { useUiStore } from '@/stores/ui'
import type { Tag } from '@/utils/mail'

const props = defineProps<{
  messageId: number
  messageTags: number[]
  allTags: Tag[]
}>()

const emit = defineEmits<{
  /** 标签更新成功后通知父组件同步本地状态 */
  update: [tagIds: number[]]
}>()

const ui = useUiStore()
const dropdownRef = ref<HTMLElement | null>(null)
const isOpen = ref(false)
const saving = ref(false)

onClickOutside(dropdownRef, () => {
  isOpen.value = false
})

function isSelected(tagId: number): boolean {
  return props.messageTags.includes(tagId)
}

/**
 * 切换标签：全量 PUT 提交。
 * 成功 → emit update 让父组件同步；失败 → toast 提示，不改本地状态
 * （下次展开会重新读服务端值，保证一致）。
 */
async function toggleTag(tag: Tag): Promise<void> {
  if (saving.value) return
  const next = isSelected(tag.id)
    ? props.messageTags.filter((id) => id !== tag.id)
    : [...props.messageTags, tag.id]

  saving.value = true
  try {
    const { error } = await api.PUT('/messages/{id}/tags', {
      params: { path: { id: props.messageId } },
      body: { tagIds: next },
    })
    // problemMiddleware 对 4xx/5xx 直接 throw，这里 error 恒为 undefined
    if (error) return
    emit('update', next)
  } catch {
    // PUT 失败：不改本地状态，提示用户
    ui.pushToast('error', '标签更新失败，请重试')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div ref="dropdownRef" class="relative inline-block">
    <button
      type="button"
      class="flex items-center gap-1.5 text-xs text-stone-grey hover:text-deep-charcoal transition"
      @click="isOpen = !isOpen"
    >
      <i class="fa-solid fa-tag text-[10px]" />
      <span>标签{{ messageTags.length > 0 ? ` (${messageTags.length})` : '' }}</span>
    </button>

    <!-- 下拉多选面板 -->
    <div
      v-if="isOpen"
      class="absolute z-50 mt-1 w-48 bg-white border border-border rounded-xl shadow-lg hygge-card max-h-60 overflow-y-auto"
    >
      <div v-if="allTags.length === 0" class="px-3 py-2.5 text-xs text-ink-400">暂无标签</div>
      <div
        v-for="tag in allTags"
        :key="tag.id"
        class="flex items-center gap-2 px-3 py-2 hover:bg-light-beige cursor-pointer text-xs"
        @click="toggleTag(tag)"
      >
        <input
          type="checkbox"
          :checked="isSelected(tag.id)"
          class="accent-sage pointer-events-none"
          readonly
        />
        <span class="text-dark-stone truncate">{{ tag.displayName }}</span>
      </div>
    </div>
  </div>
</template>
