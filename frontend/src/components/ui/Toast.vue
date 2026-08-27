<script setup lang="ts">
import { computed } from 'vue'
import type { ToastItem } from '@/stores/ui'

/**
 * 单个 toast 渲染。App.vue 里 v-for 渲染队列。
 * 三种类型：success（sage）/ error（danger）/ info（neutral），各配图标。
 */
const props = defineProps<{ toast: ToastItem }>()
const emit = defineEmits<{ close: [] }>()

const STYLE = {
  success: { icon: 'fa-solid fa-circle-check', colorClass: 'text-sage' },
  error: { icon: 'fa-solid fa-circle-exclamation', colorClass: 'text-danger-text' },
  info: { icon: 'fa-solid fa-circle-info', colorClass: 'text-stone-grey' },
} as const

// toast.type 是 'success'|'error'|'info' 精确联合，as const 对象无 undefined 风险
const toastStyle = computed(() => STYLE[props.toast.type])
</script>

<template>
  <div
    class="flex items-start gap-2.5 px-4 py-3 bg-white rounded-xl border border-border shadow-md hygge-card min-w-[260px] max-w-sm"
  >
    <i :class="[toastStyle.icon, toastStyle.colorClass]" class="mt-0.5 shrink-0" aria-hidden="true" />
    <p class="flex-1 text-xs text-dark-stone leading-relaxed">{{ toast.message }}</p>
    <button
      type="button"
      class="text-ink-300 hover:text-stone-grey transition shrink-0"
      @click="emit('close')"
      aria-label="关闭"
    >
      <i class="fa-solid fa-xmark text-xs" />
    </button>
  </div>
</template>
