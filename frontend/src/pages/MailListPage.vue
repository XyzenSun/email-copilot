<script setup lang="ts">
/**
 * 邮件列表页。`/`（direction=inbound）与 `/sent`（direction=outbound）共用，
 * 从 route.meta.direction 取方向。FRONTEND.md §3.2、§3.8。
 *
 * 顶部工具栏：账号切换、分类 tab（六分类，spam 单独 tab）、标签筛选（单选下拉）、
 * 时间范围。批量删除（ConfirmDialog 二次确认）。分页。
 *
 * classifyEnabled / taggingEnabled 关闭说明：
 * 从 GET /settings/pipeline 只读这两个开关——关掉时对应区域给说明而非静默空列表。
 * 分类 tab 旁标注「自动分类已关闭」；标签区标注「自动标注已关闭但仍可手动打标签」。
 *
 * 点击列表项 → router.push({ query: { ...当前query, thread, msg } })，留在当前路由。
 * 监听 route.query.thread 同步 uiStore.currentThreadId。
 */
import { ref, computed, watch, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { api } from '@/api/client'
import { isProblem } from '@/api/errors'
import { useUiStore } from '@/stores/ui'
import { useTags } from '@/composables/useTags'
import { useMailAccounts } from '@/composables/useMailAccounts'
import {
  CATEGORY_TABS,
  type MessageSummary,
  type Category,
} from '@/utils/mail'
import MailListItem from '@/components/mail/MailListItem.vue'
import Spin from '@/components/ui/Spin.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'

const route = useRoute()
const router = useRouter()
const ui = useUiStore()
const { tags, fetchTags } = useTags()
const { mailAccounts, fetchMailAccounts } = useMailAccounts()

// ── 方向（inbox / sent） ──
const direction = computed<'inbound' | 'outbound'>(() => {
  const d = route.meta.direction
  return d === 'outbound' ? 'outbound' : 'inbound'
})
const isInbound = computed(() => direction.value === 'inbound')

// ── 筛选条件 ──
/** 账号切换：绑定 uiStore.currentMailAccountId（跨页面保持） */
const selectedAccountId = computed<number | null>({
  get: () => ui.currentMailAccountId,
  set: (v) => {
    ui.currentMailAccountId = v
  },
})

/** 分类筛选：null=全部 */
const selectedCategory = ref<Category | null>(null)

/** 标签筛选：null=全部标签 */
const selectedTagId = ref<number | null>(null)

/** 时间范围 */
const receivedAfter = ref('')
const receivedBefore = ref('')

// ── 流水线开关（只读，控制分类/标签区域的说明文字） ──
const classifyEnabled = ref(true)
const taggingEnabled = ref(true)

// ── 列表数据 ──
const items = ref<MessageSummary[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const loading = ref(false)

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

// ── 批量选择 ──
const checkedIds = ref<Set<number>>(new Set())
const showBatchDelete = ref(false)

const hasChecked = computed(() => checkedIds.value.size > 0)

// ── 当前选中的会话（高亮列表项） ──
const currentThread = computed(() => {
  const t = route.query.thread
  if (typeof t === 'string') {
    const n = Number(t)
    if (!isNaN(n) && n > 0) return n
  }
  return null
})

/** 选了 spam tab 时需要 includeSpam=true（默认排除 spam） */
const includeSpam = computed(() => selectedCategory.value === 'spam')

/** 构建查询参数 */
function buildQuery() {
  const query: Record<string, unknown> = {
    direction: direction.value,
    page: page.value,
    size: pageSize,
  }
  if (selectedAccountId.value != null) query.accountId = selectedAccountId.value
  // spam tab 需同时设 category=spam + includeSpam=true
  if (selectedCategory.value) query.category = selectedCategory.value
  if (includeSpam.value) query.includeSpam = true
  if (selectedTagId.value != null) query.tagId = selectedTagId.value
  if (receivedAfter.value) query.receivedAfter = receivedAfter.value
  if (receivedBefore.value) query.receivedBefore = receivedBefore.value
  return query
}

/** 加载列表 */
async function loadList(): Promise<void> {
  loading.value = true
  try {
    const { data, error } = await api.GET('/messages', {
      params: { query: buildQuery() },
    })
    if (error || !data) return
    items.value = data.items
    total.value = data.total
  } catch {
    // problemMiddleware 抛错时静默（401 已被全局拦截处理）
  } finally {
    loading.value = false
  }
}

/** 加载流水线开关（只读 classifyEnabled / taggingEnabled） */
async function loadPipelineSettings(): Promise<void> {
  try {
    const { data, error } = await api.GET('/settings/pipeline')
    if (error || !data) return
    classifyEnabled.value = data.classifyEnabled
    taggingEnabled.value = data.taggingEnabled
  } catch {
    // 读取失败时默认 true（不影响使用，只是少了关闭说明）
  }
}

/** 点击列表项 → 追加 query（留在当前路由） */
function onSelectItem(threadId: number, messageId: number): void {
  void router.push({
    query: {
      ...route.query,
      thread: String(threadId),
      msg: String(messageId),
    },
  })
}

/** 批量选择 toggle */
function toggleCheck(id: number): void {
  if (checkedIds.value.has(id)) {
    checkedIds.value.delete(id)
  } else {
    checkedIds.value.add(id)
  }
}

/** 批量删除 */
async function doBatchDelete(): Promise<void> {
  const ids = [...checkedIds.value]
  if (ids.length === 0) return
  try {
    const { data, error } = await api.POST('/messages/batch-delete', {
      body: { ids },
    })
    if (error || !data) return
    // 宽松语义：按返回计数提示
    ui.pushToast(
      'success',
      `已删除 ${data.deleted} 封` +
        (data.alreadyDeleted > 0 ? `（${data.alreadyDeleted} 封此前已删除）` : ''),
    )
    checkedIds.value.clear()
    await loadList()
  } catch (e) {
    if (isProblem(e)) {
      ui.pushToast('error', '删除失败')
    }
  }
}

/** 切换分类 tab */
function selectCategory(cat: Category | null): void {
  selectedCategory.value = cat
  page.value = 1
}

/** 翻页 */
function prevPage(): void {
  if (page.value > 1) {
    page.value--
  }
}
function nextPage(): void {
  if (page.value < totalPages.value) {
    page.value++
  }
}

// ── 监听筛选条件变化 → 重新加载（reset 到第 1 页） ──
watch(
  [selectedAccountId, selectedCategory, selectedTagId, receivedAfter, receivedBefore, direction],
  () => {
    page.value = 1
    checkedIds.value.clear()
    void loadList()
  },
)

watch(page, () => {
  void loadList()
})

// ── 同步 route.query.thread 到 uiStore.currentThreadId ──
watch(
  () => route.query.thread,
  (t) => {
    ui.currentThreadId = typeof t === 'string' ? Number(t) || null : null
  },
  { immediate: true },
)

onMounted(async () => {
  await Promise.all([fetchTags(), fetchMailAccounts(), loadPipelineSettings()])
  await loadList()
})
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden">
    <!-- 顶部工具栏 -->
    <div class="border-b border-border px-3 py-2.5 space-y-2 shrink-0">
      <!-- 账号切换 -->
      <select
        v-model="selectedAccountId"
        class="w-full hygge-input rounded-xl px-3 py-1.5 text-xs"
      >
        <option :value="null">全部账号</option>
        <option v-for="acc in mailAccounts" :key="acc.id" :value="acc.id">
          {{ acc.emailAddress }}
        </option>
      </select>

      <!-- 分类 tab（仅 inbound；outbound 不进流水线，无分类） -->
      <div v-if="isInbound" class="flex items-center gap-1 flex-wrap">
        <button
          v-for="tab in CATEGORY_TABS"
          :key="tab.label"
          type="button"
          :class="[
            'px-2.5 py-1 rounded-lg text-[11px] font-medium transition',
            selectedCategory === tab.value
              ? 'bg-dark-stone text-warm-white'
              : 'text-stone-grey hover:bg-light-beige',
          ]"
          @click="selectCategory(tab.value)"
        >
          {{ tab.label }}
        </button>
        <!-- classifyEnabled 关闭说明 -->
        <span v-if="!classifyEnabled" class="text-[10px] text-clay ml-1">自动分类已关闭</span>
      </div>

      <!-- 标签筛选 + 时间范围 -->
      <div class="flex items-center gap-2">
        <select
          v-model="selectedTagId"
          class="flex-1 hygge-input rounded-xl px-3 py-1.5 text-xs"
        >
          <option :value="null">全部标签</option>
          <option v-for="tag in tags" :key="tag.id" :value="tag.id">
            {{ tag.displayName }} ({{ tag.messageCount }})
          </option>
        </select>
      </div>
      <!-- taggingEnabled 关闭说明 -->
      <p v-if="!taggingEnabled" class="text-[10px] text-clay">
        自动标注已关闭，但仍可手动打标签
      </p>

      <!-- 时间范围 -->
      <div class="flex items-center gap-2">
        <input
          v-model="receivedAfter"
          type="date"
          class="flex-1 hygge-input rounded-xl px-2.5 py-1.5 text-xs"
        />
        <span class="text-ink-300 text-xs">—</span>
        <input
          v-model="receivedBefore"
          type="date"
          class="flex-1 hygge-input rounded-xl px-2.5 py-1.5 text-xs"
        />
      </div>

      <!-- 批量删除按钮 -->
      <div v-if="hasChecked" class="flex items-center gap-2">
        <button
          type="button"
          class="flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-medium bg-danger-bg text-danger-text border border-danger-border hover:bg-[#fecaca] transition"
          @click="showBatchDelete = true"
        >
          <i class="fa-solid fa-trash text-[10px]" />
          删除选中 ({{ checkedIds.size }})
        </button>
        <button
          type="button"
          class="text-xs text-ink-400 hover:text-stone-grey transition"
          @click="checkedIds.clear()"
        >
          取消选择
        </button>
      </div>
    </div>

    <!-- 列表 -->
    <div class="flex-1 overflow-y-auto">
      <div v-if="loading" class="flex items-center justify-center py-12">
        <Spin size="text-xl" />
      </div>
      <EmptyState
        v-else-if="items.length === 0"
        icon="fa-regular fa-folder-open"
        title="没有邮件"
        description="当前筛选条件下没有邮件"
      />
      <template v-else>
        <MailListItem
          v-for="msg in items"
          :key="msg.id"
          :message="msg"
          :selected="currentThread === msg.threadId"
          :checked="checkedIds.has(msg.id)"
          :all-tags="tags"
          @select="onSelectItem"
          @toggle-check="toggleCheck"
        />
      </template>
    </div>

    <!-- 分页 -->
    <div
      v-if="total > 0"
      class="border-t border-border px-3 py-2 flex items-center justify-between shrink-0"
    >
      <button
        type="button"
        class="text-xs text-stone-grey hover:text-deep-charcoal disabled:text-ink-300 transition"
        :disabled="page <= 1"
        @click="prevPage"
      >
        上一页
      </button>
      <span class="text-[10px] text-ink-400 font-mono">{{ page }} / {{ totalPages }}</span>
      <button
        type="button"
        class="text-xs text-stone-grey hover:text-deep-charcoal disabled:text-ink-300 transition"
        :disabled="page >= totalPages"
        @click="nextPage"
      >
        下一页
      </button>
    </div>

    <!-- 批量删除确认 -->
    <ConfirmDialog
      v-model="showBatchDelete"
      title="批量删除邮件"
      :message="`确定删除选中的 ${checkedIds.size} 封邮件吗？删除后从列表中消失，但会话节点保留以维持后续回复归并。`"
      confirm-text="删除"
      danger
      @confirm="doBatchDelete"
    />
  </div>
</template>
