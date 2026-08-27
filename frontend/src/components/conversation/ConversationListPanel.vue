<script setup lang="ts">
/**
 * 对话列表中栏面板（可复用组件）。
 *
 * 从 ConversationsList 抽出，供 ConversationsList（列表页右栏的新对话入口旁）与
 * ConversationDetail（详情页三栏布局的中栏）共用。包含：copilot header +
 * 活跃/归档切换 + 新对话按钮 + 列表 v-for（改名/归档/删除行内操作）+ 分页。
 *
 * 列表逻辑自包含（loadList/saveRename/toggleArchive/requestDelete/doDelete），
 * onMounted 自动加载。导航与新建对话的职责交给父组件：
 *   - 点列表项 → emit select(id)，由父组件决定跳转。
 *   - 点新对话按钮 → emit new-conversation，由父组件决定打开新对话入口还是别处。
 *
 * 列表项高亮：activeId === c.id 时用 list-pick class（左侧 sage 竖条 + bg-white）。
 * 行内按钮（改名/归档/删除）点 stop 冒泡不触发 select。
 */
import { ref, computed, watch, onMounted } from 'vue'
import { api } from '@/api/client'
import { isProblem, onCode } from '@/api/errors'
import { useUiStore } from '@/stores/ui'
import { formatRelative } from '@/utils/format'
import type { ConversationSummary } from '@/utils/conversation'
import Spin from '@/components/ui/Spin.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'

const props = withDefaults(
  defineProps<{
    /** 当前选中对话 id，用于高亮（list-pick 竖条 + bg-white） */
    activeId?: number | null
    /** 列表项是否可点击选中（默认 true） */
    selectable?: boolean
  }>(),
  {
    activeId: null,
    selectable: true,
  },
)

const emit = defineEmits<{
  select: [id: number]
  'new-conversation': []
}>()

const ui = useUiStore()

// ── 列表数据 ──
const conversations = ref<ConversationSummary[]>([])
const loading = ref(true)
const showArchived = ref(false)
const page = ref(1)
const pageSize = 20
const total = ref(0)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

// ── 编辑/删除 ──
const editingId = ref<number | null>(null)
const editingTitle = ref('')
const deleteTarget = ref<ConversationSummary | null>(null)
const showDeleteConfirm = ref(false)

/** 加载对话列表 */
async function loadList(): Promise<void> {
  loading.value = true
  try {
    const { data, error } = await api.GET('/conversations', {
      params: { query: { page: page.value, size: pageSize, archived: showArchived.value } },
    })
    if (error || !data) return
    conversations.value = data.items
    total.value = data.total
  } catch {
    // 401 已被全局拦截
  } finally {
    loading.value = false
  }
}

/** 开始改名 */
function startRename(c: ConversationSummary): void {
  editingId.value = c.id
  editingTitle.value = c.title
}

/** 提交改名 */
async function saveRename(id: number): Promise<void> {
  const title = editingTitle.value.trim()
  if (!title) {
    editingId.value = null
    return
  }
  try {
    const { data, error } = await api.PATCH('/conversations/{id}', {
      params: { path: { id } },
      body: { title },
    })
    if (error || !data) return
    // 就地更新
    const idx = conversations.value.findIndex((c) => c.id === id)
    if (idx >= 0) conversations.value[idx] = data
    ui.pushToast('success', '已重命名')
  } catch (e) {
    if (isProblem(e)) {
      ui.pushToast('error', onCode(e, { VALIDATION_FAILED: () => '名称不能为空' }, () => '重命名失败') ?? '重命名失败')
    }
  } finally {
    editingId.value = null
  }
}

/** 切换归档 */
async function toggleArchive(c: ConversationSummary): Promise<void> {
  try {
    const { data, error } = await api.PATCH('/conversations/{id}', {
      params: { path: { id: c.id } },
      body: { archived: !c.archived },
    })
    if (error || !data) return
    // 归档后从当前列表移除（除非在看归档视图）
    if (data.archived !== showArchived.value) {
      conversations.value = conversations.value.filter((x) => x.id !== c.id)
    } else {
      const idx = conversations.value.findIndex((x) => x.id === c.id)
      if (idx >= 0) conversations.value[idx] = data
    }
    ui.pushToast('success', data.archived ? '已归档' : '已取消归档')
  } catch {
    ui.pushToast('error', '操作失败')
  }
}

/** 请求删除 */
function requestDelete(c: ConversationSummary): void {
  deleteTarget.value = c
  showDeleteConfirm.value = true
}

/** 确认删除 */
async function doDelete(): Promise<void> {
  if (!deleteTarget.value) return
  const id = deleteTarget.value.id
  try {
    await api.DELETE('/conversations/{id}', { params: { path: { id } } })
    conversations.value = conversations.value.filter((c) => c.id !== id)
    ui.pushToast('success', '对话已删除')
    void ui.refreshPendingBadge()
  } catch (e) {
    if (isProblem(e)) {
      const msg = onCode(
        e,
        {
          CONVERSATION_NOT_FOUND: () => '对话不存在',
          TURN_ALREADY_RUNNING: () => '还有一轮在跑，先停止再删',
        },
        () => '删除失败',
      ) ?? '删除失败'
      ui.pushToast('error', msg)
    }
  } finally {
    deleteTarget.value = null
  }
}

/** 点列表项（selectable 时 emit select） */
function onSelect(c: ConversationSummary): void {
  if (props.selectable) {
    emit('select', c.id)
  }
}

// 切归档视图时重新加载
watch(showArchived, () => {
  page.value = 1
  void loadList()
})

onMounted(() => {
  void loadList()
})
</script>

<template>
  <div class="w-80 border-r border-border bg-warm-white flex flex-col overflow-hidden shrink-0">
    <div class="px-3 py-2.5 border-b border-border flex items-center justify-between shrink-0">
      <div class="flex items-center gap-2">
        <span class="font-serif font-semibold text-sm text-deep-charcoal">copilot</span>
        <!-- 归档/活跃 切换 -->
        <button
          type="button"
          :class="[
            'text-[10px] px-2 py-0.5 rounded-full transition',
            showArchived ? 'bg-clay text-white' : 'text-ink-400 hover:text-stone-grey',
          ]"
          @click="showArchived = !showArchived"
        >
          {{ showArchived ? '归档' : '活跃' }}
        </button>
      </div>
      <button
        type="button"
        class="flex items-center gap-1.5 px-2.5 py-1 rounded-xl text-xs font-medium bg-dark-stone text-warm-white hover:bg-deep-charcoal transition"
        @click="emit('new-conversation')"
      >
        <i class="fa-solid fa-plus text-[10px]" /> 新对话
      </button>
    </div>

    <div class="flex-1 overflow-y-auto">
      <div v-if="loading" class="flex items-center justify-center py-12">
        <Spin size="text-xl" />
      </div>
      <EmptyState
        v-else-if="conversations.length === 0"
        :icon="showArchived ? 'fa-regular fa-box-archive' : 'fa-regular fa-comments'"
        :title="showArchived ? '没有归档对话' : '还没有对话'"
        description="点击右上角「新对话」开始与 AI 交流"
      />
      <template v-else>
        <div
          v-for="c in conversations"
          :key="c.id"
          :class="[
            'px-3.5 py-3 border-b border-border-soft transition hover:bg-light-beige/50',
            selectable ? 'cursor-pointer' : '',
            activeId === c.id ? 'list-pick bg-white' : '',
          ]"
          @click="onSelect(c)"
        >
          <div class="flex items-center justify-between gap-2">
            <!-- 标题 / 改名输入 -->
            <input
              v-if="editingId === c.id"
              v-model="editingTitle"
              type="text"
              class="flex-1 hygge-input rounded-lg px-2 py-1 text-xs"
              @click.stop
              @keyup.enter="saveRename(c.id)"
              @blur="saveRename(c.id)"
            >
            <span v-else class="text-xs font-medium text-dark-stone truncate flex-1">
              {{ c.title }}
            </span>
            <span class="text-[10px] text-ink-400 shrink-0">{{ formatRelative(c.updatedAt) }}</span>
          </div>
          <div class="flex items-center gap-3 mt-1 text-[10px] text-ink-400">
            <span><i class="fa-regular fa-comment text-[9px] mr-1" />{{ c.turnCount }} 轮</span>
            <!-- 未决提案数（必须显示——否则归档/删除时不知道里面挂着待发邮件） -->
            <span v-if="c.pendingActionCount > 0" class="text-clay font-medium flex items-center gap-1">
              <i class="fa-regular fa-circle-check text-[9px]" />{{ c.pendingActionCount }} 待审
            </span>
            <span v-if="c.archived" class="text-ink-300"><i class="fa-regular fa-box-archive text-[9px] mr-1" />归档</span>
          </div>
          <!-- 行内操作（点按钮时不触发选中） -->
          <div class="flex items-center gap-2 mt-1.5" @click.stop>
            <button
              type="button"
              class="text-[10px] text-ink-400 hover:text-stone-grey transition"
              @click="startRename(c)"
            >
              <i class="fa-solid fa-pen text-[9px] mr-0.5" />改名
            </button>
            <button
              type="button"
              class="text-[10px] text-ink-400 hover:text-stone-grey transition"
              @click="toggleArchive(c)"
            >
              <i class="fa-solid fa-box-archive text-[9px] mr-0.5" />{{ c.archived ? '取消归档' : '归档' }}
            </button>
            <button
              type="button"
              class="text-[10px] text-ink-400 hover:text-danger-text transition"
              @click="requestDelete(c)"
            >
              <i class="fa-solid fa-trash text-[9px] mr-0.5" />删除
            </button>
          </div>
        </div>
      </template>
    </div>

    <!-- 分页 -->
    <div v-if="total > pageSize" class="border-t border-border px-3 py-2 flex items-center justify-between shrink-0">
      <button
        type="button"
        class="text-xs text-stone-grey hover:text-deep-charcoal disabled:text-ink-300 transition"
        :disabled="page <= 1"
        @click="page--; loadList()"
      >
        上一页
      </button>
      <span class="text-[10px] text-ink-400 font-mono">{{ page }} / {{ totalPages }}</span>
      <button
        type="button"
        class="text-xs text-stone-grey hover:text-deep-charcoal disabled:text-ink-300 transition"
        :disabled="page >= totalPages"
        @click="page++; loadList()"
      >
        下一页
      </button>
    </div>

    <!-- 删除确认 -->
    <ConfirmDialog
      v-model="showDeleteConfirm"
      title="删除对话"
      :message="deleteTarget
        ? `确定删除「${deleteTarget.title}」吗？${deleteTarget.pendingActionCount > 0 ? `该对话下 ${deleteTarget.pendingActionCount} 个未审批提案将被取消，但已发送记录保留。` : ''}删除后不可恢复。`
        : ''"
      confirm-text="删除"
      danger
      @confirm="doDelete"
    />
  </div>
</template>
