<script setup lang="ts">
/**
 * 空态占位。列表/详情无数据时展示。
 * 默认 slot 放额外内容（如操作按钮）。
 */
defineProps<{ icon?: string; title: string; description?: string }>()
</script>

<template>
  <div class="flex flex-col items-center justify-center py-12 px-4 text-center">
    <i v-if="icon" :class="icon" class="mb-3 text-3xl text-ink-300" aria-hidden="true" />
    <p class="text-sm font-medium text-stone-grey">{{ title }}</p>
    <p v-if="description" class="mt-1 text-xs text-ink-400 leading-relaxed">{{ description }}</p>
    <slot />
  </div>
</template>
