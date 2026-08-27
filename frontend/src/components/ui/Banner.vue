<script setup lang="ts">
import { computed } from 'vue'

/**
 * 常驻横幅与提示框复用。默认密码横幅（warn）、危险提示（danger）、信息提示（info）。
 * 默认 slot 放正文，#action slot 放操作链接（如「去修改密码 →」）。
 */
const props = defineProps<{
  variant: 'warn' | 'danger' | 'info'
  icon?: string
}>()

const VARIANT_STYLE = {
  warn: 'bg-warn-bg border-warn-border text-warn-text',
  danger: 'bg-danger-bg border-danger-border text-danger-text',
  info: 'bg-light-beige border-border text-stone-grey',
} as const

const VARIANT_ICON = {
  warn: 'fa-solid fa-triangle-exclamation',
  danger: 'fa-solid fa-circle-exclamation',
  info: 'fa-solid fa-circle-info',
} as const

const containerClass = computed(
  () => VARIANT_STYLE[props.variant] ?? 'bg-light-beige border-border text-stone-grey',
)
const iconClass = computed(
  () => props.icon ?? VARIANT_ICON[props.variant] ?? 'fa-solid fa-circle-info',
)
</script>

<template>
  <div
    :class="['flex items-center justify-between gap-3 px-5 py-2 border-b text-xs z-50 shrink-0', containerClass]"
  >
    <div class="flex items-center gap-2 min-w-0">
      <i :class="iconClass" class="shrink-0" aria-hidden="true" />
      <span class="min-w-0"><slot /></span>
    </div>
    <slot name="action" />
  </div>
</template>
