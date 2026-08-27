<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import Modal from './Modal.vue'

/**
 * 确认对话框。基于 Modal，二次确认不可逆操作（批量删除、删账号等）。
 *
 * requireTextInput=true 时需用户手输文本匹配 requireTextMatch 才能点确认
 * （用于删账号核对邮箱地址——物理销毁不可逆，光点按钮不够安全）。
 * danger=true 时确认按钮用危险色系。
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title: string
    message?: string
    confirmText?: string
    danger?: boolean
    requireTextInput?: boolean
    /** 需手输匹配的文本（如邮箱地址），requireTextInput=true 时生效 */
    requireTextMatch?: string
  }>(),
  { confirmText: '确认', danger: false, requireTextInput: false },
)

const emit = defineEmits<{
  'update:modelValue': [boolean]
  confirm: []
  cancel: []
}>()

const inputText = ref('')

const canConfirm = computed(() => {
  if (!props.requireTextInput) return true
  return inputText.value === (props.requireTextMatch ?? '')
})

// 每次打开清空输入，避免上次残留导致误确认
watch(
  () => props.modelValue,
  (open) => {
    if (open) inputText.value = ''
  },
)

function onConfirm(): void {
  if (!canConfirm.value) return
  emit('confirm')
  emit('update:modelValue', false)
}

function onCancel(): void {
  emit('cancel')
  emit('update:modelValue', false)
}

// Modal 自身关闭（遮罩点击 / ESC）等价于取消
function onModalClose(value: boolean): void {
  if (!value) onCancel()
}
</script>

<template>
  <Modal :model-value="modelValue" :title="title" @update:model-value="onModalClose">
    <p v-if="message" class="text-sm text-dark-stone leading-relaxed">{{ message }}</p>

    <div v-if="requireTextInput" class="mt-3">
      <label class="block text-xs text-stone-grey mb-1.5">
        请输入 <code class="font-mono text-clay">{{ requireTextMatch }}</code> 以确认
      </label>
      <input
        v-model="inputText"
        type="text"
        class="w-full hygge-input rounded-xl px-3 py-2 text-sm"
        :placeholder="requireTextMatch"
        autocomplete="off"
      />
    </div>

    <template #footer>
      <button
        type="button"
        class="px-4 py-2 rounded-xl text-xs font-medium text-stone-grey hover:bg-light-beige transition"
        @click="onCancel"
      >
        取消
      </button>
      <button
        type="button"
        :disabled="!canConfirm"
        :class="[
          'px-4 py-2 rounded-xl text-xs font-medium transition',
          danger
            ? 'bg-danger-bg text-danger-text border border-danger-border hover:bg-[#fecaca] disabled:opacity-40 disabled:cursor-not-allowed'
            : 'bg-dark-stone text-warm-white hover:bg-deep-charcoal disabled:opacity-40 disabled:cursor-not-allowed',
        ]"
        @click="onConfirm"
      >
        {{ confirmText }}
      </button>
    </template>
  </Modal>
</template>
