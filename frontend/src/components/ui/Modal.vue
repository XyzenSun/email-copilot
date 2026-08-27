<script setup lang="ts">
import { watch, onUnmounted } from 'vue'

/**
 * 模态对话框。teleport 到 body，遮罩点击 / ESC 关闭。
 * v-model 控制开关；title 可选；默认 slot 放主体，#footer slot 放操作按钮。
 * ConfirmDialog 等基于此扩展。
 */
const props = defineProps<{
  modelValue: boolean
  title?: string
}>()
const emit = defineEmits<{ 'update:modelValue': [boolean] }>()

function close(): void {
  emit('update:modelValue', false)
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape' && props.modelValue) {
    close()
  }
}

// 打开时锁背景滚动 + 监听 ESC；关闭时还原。组件卸载时兜底清理，防止残留。
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      document.addEventListener('keydown', onKeydown)
      document.body.style.overflow = 'hidden'
    } else {
      document.removeEventListener('keydown', onKeydown)
      document.body.style.overflow = ''
    }
  },
)

onUnmounted(() => {
  document.removeEventListener('keydown', onKeydown)
  document.body.style.overflow = ''
})
</script>

<template>
  <Teleport to="body">
    <div v-if="modelValue" class="fixed inset-0 z-[100] flex items-center justify-center p-4">
      <div class="absolute inset-0 bg-deep-charcoal/30" @click="close" />
      <div
        class="relative w-full max-w-md bg-white rounded-2xl border border-border shadow-lg hygge-card max-h-[90vh] flex flex-col"
      >
        <div
          v-if="title"
          class="flex items-center justify-between px-5 py-4 border-b border-border-soft shrink-0"
        >
          <h3 class="text-sm font-serif font-semibold text-deep-charcoal">{{ title }}</h3>
          <button
            type="button"
            class="text-ink-300 hover:text-stone-grey transition"
            @click="close"
            aria-label="关闭"
          >
            <i class="fa-solid fa-xmark" />
          </button>
        </div>
        <div class="overflow-y-auto px-5 py-4">
          <slot />
        </div>
        <div
          v-if="$slots.footer"
          class="px-5 py-3 border-t border-border-soft flex justify-end gap-2 shrink-0"
        >
          <slot name="footer" />
        </div>
      </div>
    </div>
  </Teleport>
</template>
