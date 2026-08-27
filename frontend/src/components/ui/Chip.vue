<script setup lang="ts">
import { computed } from 'vue'

/**
 * 小标签胶囊。四种变体：分类 / 标签 / 计数 / 状态。
 *
 * 分类色照原型 CAT_STYLE 集中映射（primary 中性 / transaction 绿 / promotion 黄 /
 * social 紫 / update 蓝 / spam 红），不进 theme token——照原型做法。
 * category 变体传 category prop 内部映射；status 变体传 color prop。
 */
const props = withDefaults(
  defineProps<{
    variant: 'category' | 'tag' | 'count' | 'status'
    /** category 变体：邮件分类名（primary/transaction/promotion/social/update/spam） */
    category?: string
    /** status 变体：状态色名 */
    color?: 'sage' | 'clay' | 'danger' | 'neutral'
  }>(),
  { color: 'neutral' },
)

/** 分类配色映射（照原型 CAT_STYLE） */
const CAT_STYLE: Record<string, string> = {
  primary: 'bg-light-beige text-stone-grey border-border',
  transaction: 'bg-[#f0f4ef] text-[#5b6b50] border-[#dfe6dc]',
  promotion: 'bg-warn-bg text-warn-text border-warn-border',
  social: 'bg-[#f3f1f6] text-[#6b5f7a] border-[#e5e1ea]',
  update: 'bg-[#f1f3f5] text-[#5a6570] border-[#e3e7ea]',
  spam: 'bg-danger-bg text-danger-text border-danger-border',
}

const STATUS_STYLE = {
  sage: 'bg-[#f0f4ef] text-sage border-[#dfe6dc]',
  clay: 'bg-warn-bg text-clay border-warn-border',
  danger: 'bg-danger-bg text-danger-text border-danger-border',
  neutral: 'bg-light-beige text-stone-grey border-border',
} as const

const chipClass = computed(() => {
  const base = 'inline-flex items-center rounded-full border whitespace-nowrap'
  if (props.variant === 'category') {
    const style =
      (props.category != null && CAT_STYLE[props.category]) || CAT_STYLE['primary'] || base
    return `${base} px-2 py-0.5 text-[9px] ${style}`
  }
  if (props.variant === 'tag') {
    return `${base} px-2 py-0.5 text-[10px] bg-light-beige text-stone-grey border-border`
  }
  if (props.variant === 'count') {
    return `${base} px-2 py-0.5 text-[10px] font-mono font-bold bg-light-beige text-deep-charcoal border-border`
  }
  // status
  return `${base} px-2 py-0.5 text-[10px] ${STATUS_STYLE[props.color]}`
})
</script>

<template>
  <span :class="chipClass"><slot /></span>
</template>
