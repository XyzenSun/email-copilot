<script setup lang="ts">
/**
 * 搜索页 `/search`。FRONTEND.md §3.4、design §8.4。
 *
 * 关键词检索（多词按 AND）+ 字段单选（any/body/subject/sender）+ 过滤
 * （账号/时间/分类/标签/附件/spam）。
 *
 * snippet 是后端返回的命中处上下文**纯文本**（不含 HTML）。前端用 highlightSnippet
 * 按关键词切分、模板用 <mark> 渲染命中处——**禁 v-html**（邮件正文/snippet 完全由
 * 攻击者控制）。highlightSnippet 产出的是结构（分段数组）而非 HTML 字符串。
 *
 * 点结果留在当前路由（/search），追加 ?thread=&msg=，中栏列表与筛选条件原地不动——
 * 与 MailListPage 一致（design §4.3：点列表项 → 追加 thread/msg 到 current query）。
 * 关闭详情只去掉 query，搜索词不丢（FRONTEND.md §2 路由设计的核心理由：做成独立页面
 * 的话，从搜索结果点开再返回，q 就丢了）。
 *
 * q 写进 route.query.q（可书签可刷新，顶栏搜索框也跳这里）；其余筛选为页面本地状态。
 * 错误据 code 不据 title（那是可改文案）：VALIDATION_FAILED → 提示输入有误，不回显输入。
 */
import { ref, computed, watch, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { api } from '@/api/client'
import { isProblem, onCode } from '@/api/errors'
import { useUiStore } from '@/stores/ui'
import { useTags } from '@/composables/useTags'
import { useMailAccounts } from '@/composables/useMailAccounts'
import { CATEGORY_TABS, type MessageSummary, type Category } from '@/utils/mail'
import type { components, operations } from '@/api/types.gen'
import MailListItem from '@/components/mail/MailListItem.vue'
import Spin from '@/components/ui/Spin.vue'
import EmptyState from '@/components/ui/EmptyState.vue'

/** GET /search 的 query 形状（openapi 契约，不手写）。q 必填，其余可选。 */
type SearchQuery = operations['searchMessages']['parameters']['query']
type SearchField = components['schemas']['SearchField'] // "any"|"body"|"subject"|"sender"
type SortOrder = components['schemas']['SortOrder'] // "desc"|"asc"

/** 字段单选 tab：any=正文+标题、sender=发件人前缀整体匹配（其余走 Lucene 词项）。 */
const FIELD_TABS: ReadonlyArray<{ value: SearchField; label: string }> = [
  { value: 'any', label: '正文+标题' },
  { value: 'body', label: '正文' },
  { value: 'subject', label: '主题' },
  { value: 'sender', label: '发件人' },
]

const route = useRoute()
const router = useRouter()
const ui = useUiStore()
const { tags, fetchTags } = useTags()
const { mailAccounts, fetchMailAccounts } = useMailAccounts()

/** 搜索输入框文本（草稿；回车提交后才写进 URL）。从 route.query.q 初始化。 */
const qInput = ref(String(route.query.q ?? ''))

/** 已提交的关键词——以 URL 为准（可书签可刷新）。空串表示尚未提交/已清空。 */
const currentQ = computed(() => {
  const q = route.query.q
  return typeof q === 'string' ? q.trim() : ''
})

/** 按空格切分关键词，供 highlightSnippet 加粗（多词各自独立高亮）。 */
const searchKeywords = computed(() => currentQ.value.split(/\s+/).filter(Boolean))

/** 是否有可搜索的关键词（无则不发起请求，给提示而非等 400）。 */
const hasQuery = computed(() => currentQ.value.length > 0)

// ── 筛选条件（页面本地状态） ──
const field = ref<SearchField>('any')
const order = ref<SortOrder>('desc')
const selectedAccountId = ref<number | null>(null)
const selectedCategory = ref<Category | null>(null)
const selectedTagId = ref<number | null>(null)
const receivedAfter = ref('')
const receivedBefore = ref('')
const hasAttachmentChecked = ref(false)
const includeSpamToggle = ref(false)

// ── 结果数据 ──
const items = ref<MessageSummary[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const loading = ref(false)

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

/** 选了垃圾分类时强制 includeSpam（否则 spam 被默认排除、查不到）；其余跟随开关。 */
const effectiveIncludeSpam = computed(
  () => includeSpamToggle.value || selectedCategory.value === 'spam',
)

/** 当前右栏打开的会话 id（高亮命中的结果项，与列表页一致）。 */
const currentThread = computed(() => {
  const t = route.query.thread
  if (typeof t === 'string') {
    const n = Number(t)
    if (!isNaN(n) && n > 0) return n
  }
  return null
})

/** 字段说明：any/body/subject 走 Lucene 词项，sender 走 PG 前缀整体匹配。 */
const fieldNote = computed(() => {
  switch (field.value) {
    case 'any':
      return '匹配正文与主题（多词按 AND）'
    case 'body':
      return '仅匹配正文词项'
    case 'subject':
      return '仅匹配主题词项'
    case 'sender':
      return '发件人地址/名称前缀整体匹配'
  }
})

/** 构建搜索 query（openapi 契约类型）。只发非默认值，保持请求干净。 */
function buildQuery(): SearchQuery {
  const query: SearchQuery = {
    q: currentQ.value,
    page: page.value,
    size: pageSize,
  }
  if (field.value !== 'any') query.field = field.value
  if (order.value !== 'desc') query.order = order.value
  if (selectedAccountId.value != null) query.accountId = selectedAccountId.value
  if (selectedCategory.value) query.category = selectedCategory.value
  if (selectedTagId.value != null) query.tagId = selectedTagId.value
  if (receivedAfter.value) query.receivedAfter = receivedAfter.value
  if (receivedBefore.value) query.receivedBefore = receivedBefore.value
  if (hasAttachmentChecked.value) query.hasAttachment = true
  if (effectiveIncludeSpam.value) query.includeSpam = true
  return query
}

/** 加载搜索结果。空关键词不发起请求（清空结果），避免无谓的 400。 */
async function loadSearch(): Promise<void> {
  if (!hasQuery.value) {
    items.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const { data, error } = await api.GET('/search', {
      params: { query: buildQuery() },
    })
    if (error || !data) return
    items.value = data.items
    total.value = data.total
  } catch (e) {
    // 据错 code 分支，不据 title（可改文案）；VALIDATION_FAILED 不回显输入内容
    if (isProblem(e)) {
      onCode(
        e,
        { VALIDATION_FAILED: () => ui.pushToast('error', '搜索输入有误，请检查关键词') },
        () => ui.pushToast('error', '搜索失败'),
      )
    } else {
      ui.pushToast('error', '搜索失败')
    }
  } finally {
    loading.value = false
  }
}

/** 回车提交：把输入框文本写进 URL（可书签可刷新），并关掉右栏旧会话。 */
function submitSearch(): void {
  const q = qInput.value.trim()
  qInput.value = q
  // 新搜索丢掉 ?thread/?msg=（右栏旧会话不再相关），仅保留 q（筛选是本地状态不受影响）
  void router.replace({ query: q ? { q } : {} })
}

function selectField(f: SearchField): void {
  field.value = f
}

function toggleOrder(): void {
  order.value = order.value === 'desc' ? 'asc' : 'desc'
}

function prevPage(): void {
  if (page.value > 1) page.value--
}
function nextPage(): void {
  if (page.value < totalPages.value) page.value++
}

/** 点结果 → 追加 ?thread=&msg=，留在 /search（保留 q 与筛选），右栏展开会话详情。
 *  与 MailListPage 一致：spread 当前 query 再追加，搜索词不丢。 */
function onSelectItem(threadId: number, messageId: number): void {
  void router.push({
    query: {
      ...route.query,
      thread: String(threadId),
      msg: String(messageId),
    },
  })
}

// ── 监听：筛选条件变化 → 回到第 1 页重新搜索 ──
// page>1 时只改 page（由下方 page-watch 加载），page==1 时直接加载；避免双次请求。
watch(
  [
    field,
    order,
    selectedAccountId,
    selectedCategory,
    selectedTagId,
    receivedAfter,
    receivedBefore,
    hasAttachmentChecked,
    includeSpamToggle,
  ],
  () => {
    if (page.value > 1) {
      page.value = 1
    } else {
      void loadSearch()
    }
  },
)

watch(page, () => {
  void loadSearch()
})

// 外部 q 变化（顶栏搜索 / 浏览器前进后退 / 本页 submitSearch）→
// 同步输入框 + 回到第 1 页重新搜索
watch(
  () => route.query.q,
  (newQ) => {
    qInput.value = typeof newQ === 'string' ? newQ : ''
    if (page.value > 1) {
      page.value = 1
    } else {
      void loadSearch()
    }
  },
)

onMounted(() => {
  // 首屏若有 q 立即搜索；标签/账号列表并行拉取（下拉用，不阻塞搜索）
  void loadSearch()
  void Promise.all([fetchTags(), fetchMailAccounts()])
})
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden">
    <!-- 顶部工具栏 -->
    <div class="border-b border-border px-3 py-2.5 space-y-2 shrink-0">
      <!-- 关键词输入（回车提交，写进 URL 可书签） -->
      <form @submit.prevent="submitSearch">
        <div class="relative">
          <i
            class="fa-solid fa-magnifying-glass absolute left-3 top-1/2 -translate-y-1/2 text-xs text-ink-300"
            aria-hidden="true"
          />
          <input
            v-model="qInput"
            type="search"
            class="w-full hygge-input rounded-xl pl-9 pr-3 py-2 text-xs"
            placeholder="关键词（多词按 AND）"
            aria-label="搜索关键词"
          />
        </div>
      </form>

      <!-- 搜索默认范围：含自己发出的邮件（direction 默认 all，与列表页默认 inbound 相反） -->
      <p class="text-[10px] text-ink-400">搜索默认含自己发出的邮件</p>

      <!-- 字段单选（any/body/subject/sender，默认 any） -->
      <div class="flex items-center gap-1 flex-wrap">
        <button
          v-for="f in FIELD_TABS"
          :key="f.value"
          type="button"
          :class="[
            'px-2.5 py-1 rounded-lg text-[11px] font-medium transition',
            field === f.value
              ? 'bg-dark-stone text-warm-white'
              : 'text-stone-grey hover:bg-light-beige',
          ]"
          @click="selectField(f.value)"
        >
          {{ f.label }}
        </button>
      </div>
      <p class="text-[10px] text-ink-400">{{ fieldNote }}</p>

      <!-- 账号 + 分类（分类下拉含「全部」，六分类） -->
      <div class="grid grid-cols-2 gap-2">
        <select
          v-model="selectedAccountId"
          class="hygge-input rounded-xl px-2.5 py-1.5 text-xs"
          aria-label="账号筛选"
        >
          <option :value="null">全部账号</option>
          <option v-for="acc in mailAccounts" :key="acc.id" :value="acc.id">
            {{ acc.emailAddress }}
          </option>
        </select>
        <select
          v-model="selectedCategory"
          class="hygge-input rounded-xl px-2.5 py-1.5 text-xs"
          aria-label="分类筛选"
        >
          <option v-for="c in CATEGORY_TABS" :key="c.label" :value="c.value">
            {{ c.label }}
          </option>
        </select>
      </div>

      <!-- 单标签下拉（多标签不做，单选） -->
      <select
        v-model="selectedTagId"
        class="w-full hygge-input rounded-xl px-2.5 py-1.5 text-xs"
        aria-label="标签筛选"
      >
        <option :value="null">全部标签</option>
        <option v-for="tag in tags" :key="tag.id" :value="tag.id">
          {{ tag.displayName }} ({{ tag.messageCount }})
        </option>
      </select>

      <!-- 时间范围（闭区间，原生日期输入够用） -->
      <div class="flex items-center gap-2">
        <input
          v-model="receivedAfter"
          type="date"
          class="flex-1 hygge-input rounded-xl px-2.5 py-1.5 text-xs"
          aria-label="开始日期"
        />
        <span class="text-ink-300 text-xs">—</span>
        <input
          v-model="receivedBefore"
          type="date"
          class="flex-1 hygge-input rounded-xl px-2.5 py-1.5 text-xs"
          aria-label="结束日期"
        />
      </div>

      <!-- 附件存在性 + includeSpam（默认排除 spam） -->
      <div class="flex items-center gap-4">
        <label class="flex items-center gap-1.5 text-[11px] text-stone-grey cursor-pointer">
          <input v-model="hasAttachmentChecked" type="checkbox" class="accent-stone-grey" />
          仅含附件
        </label>
        <label class="flex items-center gap-1.5 text-[11px] text-stone-grey cursor-pointer">
          <input v-model="includeSpamToggle" type="checkbox" class="accent-stone-grey" />
          含垃圾邮件
        </label>
      </div>
    </div>

    <!-- 结果区 -->
    <div class="flex-1 overflow-y-auto">
      <!-- 无关键词：不发起请求，给提示 -->
      <EmptyState
        v-if="!hasQuery"
        icon="fa-solid fa-magnifying-glass"
        title="输入关键词开始搜索"
        description="支持正文、主题、发件人多字段检索，多词按 AND"
      />
      <div v-else-if="loading" class="flex items-center justify-center py-12">
        <Spin size="text-xl" />
      </div>
      <EmptyState
        v-else-if="items.length === 0"
        icon="fa-regular fa-folder-open"
        title="无搜索结果"
        description="试试更换关键词或调整筛选条件"
      />
      <template v-else>
        <!-- 结果计数 + 排序切换（默认 receivedAt 倒序，可切正序） -->
        <div class="flex items-center justify-between px-3.5 py-2 border-b border-border-soft">
          <span class="text-[10px] text-ink-400">找到 {{ total }} 条结果</span>
          <button
            type="button"
            class="flex items-center gap-1 text-[10px] text-stone-grey hover:text-deep-charcoal transition"
            @click="toggleOrder"
          >
            <i
              class="fa-solid"
              :class="order === 'desc' ? 'fa-arrow-down-wide-short' : 'fa-arrow-up-wide-short'"
              aria-hidden="true"
            />
            {{ order === 'desc' ? '最新优先' : '最早优先' }}
          </button>
        </div>
        <!-- 结果项：复用 MailListItem，传 keywords 开启 snippet 加粗、showCheck=false 隐藏批量勾选。
             snippet 加粗由 highlightSnippet 产出结构 + 模板 <mark> 渲染，禁 v-html。 -->
        <MailListItem
          v-for="msg in items"
          :key="msg.id"
          :message="msg"
          :selected="currentThread === msg.threadId"
          :show-check="false"
          :keywords="searchKeywords"
          :all-tags="tags"
          @select="onSelectItem"
        />
      </template>
    </div>

    <!-- 分页（读 total；单次上限 size=20） -->
    <div
      v-if="hasQuery && total > 0"
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
  </div>
</template>
