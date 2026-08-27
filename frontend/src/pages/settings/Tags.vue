<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useUiStore } from '@/stores/ui'
import { useTags } from '@/composables/useTags'
import { api } from '@/api/client'
import { isProblem, onCode } from '@/api/errors'
import type { components } from '@/api/types.gen'
import Spin from '@/components/ui/Spin.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'

/**
 * 标签页（FRONTEND.md §3.7）。
 *
 * Tag = { id, name(创建后不可改), displayName, description, messageCount, ... }。
 *
 * description 是**写给 AI 的判定依据，不是给人看的备注**——会被原样拼进打标签
 * 时的提示词。界面必须写明这一点，否则用户随手填一句"这个标签我自己用"，
 * AI 就照着这句去判断。placeholder 要同时说清什么算、什么不算。
 *
 * 页首显示 taggingEnabled 状态（GET /settings/pipeline）。关掉时提示
 * "自动标注已关闭，这些描述暂时不生效，标签仍可手动打"。
 * **该提示不读 classifyEnabled**（分类与标签是两个独立开关）。
 *
 * 删除前显示 messageCount（"该标签在 N 封邮件上"）二次确认。
 */
type Tag = components['schemas']['Tag']
type TagUpdateRequest = components['schemas']['TagUpdateRequest']

const ui = useUiStore()
const { fetchTags: refreshSharedTags } = useTags()

/** 内联编辑状态：tag 是服务端快照，displayName/description 是本地编辑缓冲 */
interface TagEditState {
  tag: Tag
  displayName: string
  description: string
}

const editStates = ref<TagEditState[]>([])
const loading = ref(true)
const savingId = ref<number | null>(null)
/** taggingEnabled 状态：null=未加载/获取失败，不显示提示 */
const taggingEnabled = ref<boolean | null>(null)

// 删除确认
const deleteTarget = ref<TagEditState | null>(null)

// 新建表单
const showCreateForm = ref(false)
const newName = ref('')
const newDisplayName = ref('')
const newDescription = ref('')
const creating = ref(false)
const createError = ref<string | null>(null)

async function loadTags(): Promise<void> {
  loading.value = true
  try {
    const { data, error } = await api.GET('/tags')
    if (error || !data) return
    editStates.value = data.items.map((tag) => ({
      tag,
      displayName: tag.displayName,
      description: tag.description,
    }))
  } catch {
    // 401 由全局 middleware 处理
  } finally {
    loading.value = false
  }
}

async function loadPipelineSettings(): Promise<void> {
  try {
    const { data, error } = await api.GET('/settings/pipeline')
    if (error || !data) return
    taggingEnabled.value = data.taggingEnabled
  } catch {
    // 获取失败不影响标签管理，只是不显示开关状态提示
  }
}

function isDirty(item: TagEditState): boolean {
  return (
    item.displayName !== item.tag.displayName ||
    item.description !== item.tag.description
  )
}

async function saveTag(item: TagEditState): Promise<void> {
  if (savingId.value !== null) return
  // 只传改动字段（TagUpdateRequest 全可选）
  const body: TagUpdateRequest = {}
  if (item.displayName !== item.tag.displayName) {
    body.displayName = item.displayName
  }
  if (item.description !== item.tag.description) {
    body.description = item.description
  }
  if (Object.keys(body).length === 0) return

  savingId.value = item.tag.id
  try {
    const { data, error } = await api.PATCH('/tags/{id}', {
      params: { path: { id: item.tag.id } },
      body,
    })
    if (error || !data) return
    // 用响应更新本地快照 + 重置编辑缓冲
    item.tag = data
    item.displayName = data.displayName
    item.description = data.description
    // 刷新共享缓存（MailListPage 的标签筛选下拉）
    void refreshSharedTags(true)
    ui.pushToast('success', '标签已更新')
  } catch (err) {
    if (isProblem(err)) {
      onCode(
        err,
        {
          TAG_NOT_FOUND: () => ui.pushToast('error', '标签不存在，可能已被删除'),
          VALIDATION_FAILED: () =>
            ui.pushToast('error', err.detail ?? '参数有误'),
        },
        () => ui.pushToast('error', '更新失败，请稍后再试'),
      )
    } else {
      ui.pushToast('error', '更新失败，请稍后再试')
    }
  } finally {
    savingId.value = null
  }
}

async function createTag(): Promise<void> {
  if (creating.value) return
  const name = newName.value.trim()
  const displayName = newDisplayName.value.trim()
  const description = newDescription.value.trim()

  // 前端基础校验（后端才是权威）
  if (!name) {
    createError.value = '请输入标识'
    return
  }
  // 与后端 pattern ^[A-Za-z0-9]+$ 对齐
  if (!/^[A-Za-z0-9]+$/.test(name)) {
    createError.value = '标识只允许英文字母与数字'
    return
  }
  if (!displayName) {
    createError.value = '请输入展示名'
    return
  }
  if (!description) {
    createError.value = '请输入描述'
    return
  }

  creating.value = true
  createError.value = null
  try {
    const { data, error } = await api.POST('/tags', {
      body: { name, displayName, description },
    })
    if (error || !data) return
    editStates.value.push({
      tag: data,
      displayName: data.displayName,
      description: data.description,
    })
    void refreshSharedTags(true)
    ui.pushToast('success', '标签已创建')
    // 重置表单
    showCreateForm.value = false
    newName.value = ''
    newDisplayName.value = ''
    newDescription.value = ''
  } catch (err) {
    if (isProblem(err)) {
      const problem = err
      createError.value =
        onCode(
          problem,
          {
            TAG_NAME_TAKEN: () => '标识已存在',
            INVALID_TAG_NAME: () => '标识只允许英文字母与数字',
            VALIDATION_FAILED: () => problem.detail ?? '参数有误',
          },
          () => '创建失败，请稍后再试',
        ) ?? '创建失败，请稍后再试'
    } else {
      createError.value = '创建失败，请稍后再试'
    }
  } finally {
    creating.value = false
  }
}

async function confirmDeleteTag(): Promise<void> {
  if (!deleteTarget.value) return
  const id = deleteTarget.value.tag.id
  try {
    await api.DELETE('/tags/{id}', { params: { path: { id } } })
    editStates.value = editStates.value.filter((s) => s.tag.id !== id)
    void refreshSharedTags(true)
    ui.pushToast('success', '标签已删除')
  } catch (err) {
    if (isProblem(err)) {
      onCode(
        err,
        {
          TAG_NOT_FOUND: () => ui.pushToast('error', '标签不存在，可能已被删除'),
        },
        () => ui.pushToast('error', '删除失败，请稍后再试'),
      )
    } else {
      ui.pushToast('error', '删除失败，请稍后再试')
    }
  } finally {
    deleteTarget.value = null
  }
}

onMounted(() => {
  void loadTags()
  void loadPipelineSettings()
})
</script>

<template>
  <div class="h-full overflow-y-auto">
    <div class="max-w-2xl mx-auto p-6 space-y-6">
      <!-- 标题区 -->
      <div class="flex items-start justify-between">
        <div>
          <h1 class="font-serif text-lg font-semibold text-deep-charcoal">自定义标签设置</h1>
          <p class="text-xs text-stone-grey mt-1 leading-relaxed">
            管理标签标识与 AI 判定依据。
          </p>
        </div>
        <button
          v-if="!showCreateForm"
          type="button"
          class="shrink-0 flex items-center gap-1.5 px-3.5 py-2 rounded-xl bg-dark-stone text-warm-white text-xs font-medium hover:bg-deep-charcoal transition"
          @click="showCreateForm = true"
        >
          <i class="fa-solid fa-plus text-[10px]" />
          新建标签
        </button>
      </div>

      <!-- taggingEnabled 关闭提示（不读 classifyEnabled） -->
      <div
        v-if="taggingEnabled === false"
        class="flex items-start gap-2 text-xs text-warn-text bg-warn-bg border border-warn-border rounded-xl px-4 py-3 leading-relaxed"
      >
        <i class="fa-solid fa-triangle-exclamation shrink-0 mt-0.5" />
        <span>自动标注已关闭，这些描述暂时不生效，标签仍可手动打。</span>
      </div>

      <!-- 新建表单（内联展开，description 需要空间） -->
      <form
        v-if="showCreateForm"
        class="bg-white rounded-2xl border border-border hygge-card p-5 space-y-4"
        @submit.prevent="createTag"
      >
        <div class="space-y-1.5">
          <label for="newName" class="block text-xs font-medium text-stone-grey">
            标识
          </label>
          <input
            id="newName"
            v-model="newName"
            type="text"
            class="w-full hygge-input rounded-xl px-3.5 py-2.5 text-sm font-mono"
            placeholder="如 invoice"
            autocomplete="off"
            :disabled="creating"
          />
          <p class="text-[10px] text-ink-400">只允许英文字母与数字，创建后不可改</p>
        </div>

        <div class="space-y-1.5">
          <label for="newDisplayName" class="block text-xs font-medium text-stone-grey">
            展示名
          </label>
          <input
            id="newDisplayName"
            v-model="newDisplayName"
            type="text"
            class="w-full hygge-input rounded-xl px-3.5 py-2.5 text-sm"
            placeholder="如 发票"
            autocomplete="off"
            :disabled="creating"
          />
        </div>

        <div class="space-y-1.5">
          <label for="newDescription" class="block text-xs font-medium text-stone-grey">
            描述
            <span class="text-clay font-normal">（给 AI 的判定规则，不是备注）</span>
          </label>
          <textarea
            id="newDescription"
            v-model="newDescription"
            rows="3"
            class="w-full hygge-input rounded-xl px-3.5 py-2.5 text-sm resize-y"
            placeholder="涉及付款、发票、账单的邮件。仅仅提到金额但不要求付款的不算。"
            :disabled="creating"
          />
          <p class="text-[10px] text-ink-400 leading-relaxed">
            这段描述决定 AI 如何判定，要说清什么算、什么不算。
          </p>
        </div>

        <p v-if="createError" class="text-xs text-danger-text flex items-center gap-1.5">
          <i class="fa-solid fa-circle-exclamation shrink-0" />
          {{ createError }}
        </p>

        <div class="flex justify-end gap-2 pt-1">
          <button
            type="button"
            class="px-4 py-2 rounded-xl text-xs font-medium text-stone-grey hover:bg-light-beige transition"
            :disabled="creating"
            @click="showCreateForm = false; createError = null"
          >
            取消
          </button>
          <button
            type="submit"
            class="flex items-center gap-1.5 px-4 py-2 rounded-xl text-xs font-medium bg-dark-stone text-warm-white hover:bg-deep-charcoal transition disabled:opacity-50 disabled:cursor-not-allowed"
            :disabled="creating || !newName.trim() || !newDisplayName.trim() || !newDescription.trim()"
          >
            <Spin v-if="creating" size="text-xs" />
            {{ creating ? '创建中…' : '创建' }}
          </button>
        </div>
      </form>

      <!-- 加载中 -->
      <div v-if="loading" class="flex items-center justify-center py-12">
        <Spin size="text-lg" />
      </div>

      <!-- 空态 -->
      <EmptyState
        v-else-if="editStates.length === 0"
        icon="fa-solid fa-tag"
        title="暂无标签"
        description="新建标签后 AI 会自动按描述给邮件标注"
      />

      <!-- 标签列表（内联编辑） -->
      <div v-else class="space-y-3">
        <div
          v-for="item in editStates"
          :key="item.tag.id"
          class="bg-white rounded-2xl border border-border hygge-card p-5 space-y-3"
        >
          <!-- 头部：标识 + 邮件数 + 操作 -->
          <div class="flex items-center gap-2">
            <span class="font-mono text-xs text-ink-500 bg-light-beige border border-border rounded px-2 py-0.5">
              {{ item.tag.name }}
            </span>
            <span class="text-[10px] text-ink-400">标识不可改</span>
            <span class="flex-1" />
            <span class="text-[10px] text-ink-400">
              {{ item.tag.messageCount }} 封邮件
            </span>
            <button
              type="button"
              class="text-xs text-ink-400 hover:text-danger-text transition"
              @click="deleteTarget = item"
            >
              <i class="fa-solid fa-trash" />
            </button>
          </div>

          <!-- 展示名 -->
          <div class="space-y-1">
            <label class="block text-[10px] font-medium text-ink-400">展示名</label>
            <input
              v-model="item.displayName"
              type="text"
              class="w-full hygge-input rounded-xl px-3 py-2 text-sm"
              :disabled="savingId === item.tag.id"
            />
          </div>

          <!-- 描述（给 AI 的判定规则） -->
          <div class="space-y-1">
            <label class="block text-[10px] font-medium text-ink-400">
              描述 <span class="text-clay">给 AI 的判定规则</span>
            </label>
            <textarea
              v-model="item.description"
              rows="2"
              class="w-full hygge-input rounded-xl px-3 py-2 text-sm resize-y"
              :placeholder="'涉及付款、发票、账单的邮件。仅仅提到金额但不要求付款的不算。'"
              :disabled="savingId === item.tag.id"
            />
          </div>

          <!-- 保存按钮（dirty 时显示） -->
          <div v-if="isDirty(item)" class="flex justify-end gap-2 pt-1 border-t border-border-soft">
            <button
              type="button"
              class="flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-medium bg-sage text-warm-white hover:opacity-90 transition disabled:opacity-50"
              :disabled="savingId === item.tag.id"
              @click="saveTag(item)"
            >
              <Spin v-if="savingId === item.tag.id" size="text-xs" />
              保存
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 删除确认 -->
    <ConfirmDialog
      :model-value="deleteTarget !== null"
      title="删除标签"
      :message="
        deleteTarget
          ? `该标签在 ${deleteTarget.tag.messageCount} 封邮件上。删除后这些邮件不再有此标签，确定删除吗？`
          : ''
      "
      confirm-text="删除"
      danger
      @confirm="confirmDeleteTag"
      @cancel="deleteTarget = null"
    />
  </div>
</template>
