<script setup lang="ts">
/**
 * 加载指示器。fa-circle-notch 旋转。
 * 用法：<Spin /> 或 <Spin size="text-lg" />
 * size 传 Tailwind 文字尺寸类（text-sm / text-base / text-lg），默认 text-base。
 */
withDefaults(defineProps<{ size?: string }>(), { size: 'text-base' })
</script>

<template>
  <i class="fa-solid fa-circle-notch animate-spin" :class="size" aria-hidden="true" />
</template>
