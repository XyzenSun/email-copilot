<script setup lang="ts">
/**
 * 对话详情页（FRONTEND.md §3.5 对话页）。
 *
 * **三栏布局**：进入对话后保持三栏（左导航由 App.vue shell 提供 + 中栏对话列表
 * ConversationListPanel + 右栏对话详情）。列表中栏常驻，点列表项切换对话
 * （props.id 变化已有 watch 重载）。
 *
 * 核心职责：
 * - GET /conversations/{id} → 全部轮次（按 startedAt 升序），一次全给不分页。
 * - **新建对话接手**：ConversationsList 启动 SSE（conversationId=null）→ start 事件
 *   导航到此页 → 此页检测单例 SSE 状态有进行中的流 → 渲染「实时轮次」。
 * - **流式渲染**：useSseTurn 单例 composable（原生 fetch + ReadableStream）。
 *   首事件 start 先存 turnId 再渲染 token（停止按钮要用）。
 * - **停止生成**：POST /turns/{id}/cancel → 204，不等真停。半截文字保存标「已停止」。
 * - **inContext:false 灰显但照常显示**（opacity-60，不隐藏）。
 * - **上下文用量条**：超 80% 警示色。limitTokens 从接口取不写死。
 * - **清除上下文**：POST /conversations/{id}/context/clear，返回体覆盖本地 context。
 * - **压缩痕迹**：compactedTurnCount>0 顶部提示；clearedAtTurnId 处分隔线。
 * - **409 TURN_ALREADY_RUNNING**：提示「当前对话还有一轮在进行」。
 * - **404**：回退 /conversations。
 */
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api/client'
import { isProblem, onCode } from '@/api/errors'
import { useUiStore } from '@/stores/ui'
import { useAuthStore } from '@/stores/auth'
import { useSseTurn } from '@/composables/useSseTurn'
import { formatTokenUsage } from '@/utils/format'
import type {
  ConversationDetail as ConversationDetailType,
  ConversationContext,
  Turn,
  PendingActionCard,
  PendingActionDetail,
} from '@/utils/conversation'
import Spin from '@/components/ui/Spin.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import ContextBar from '@/components/conversation/ContextBar.vue'
import TurnMessage from '@/components/conversation/TurnMessage.vue'
import EvidenceList from '@/components/conversation/EvidenceList.vue'
import ApprovalCard from '@/components/conversation/ApprovalCard.vue'
import ConversationListPanel from '@/components/conversation/ConversationListPanel.vue'

const props = defineProps<{ id: string }>()

const router = useRouter()
const ui = useUiStore()
const auth = useAuthStore()
const sseTurn = useSseTurn()

// ── 对话数据 ──
const conversation = ref<ConversationDetailType | null>(null)
const context = ref<ConversationContext | null>(null)
const turns = ref<Turn[]>([])
const loading = ref(true)
const notFound = ref(false)

// ── 输入 ──
const inputMessage = ref('')

// ── 清除上下文确认 ──
const showClearConfirm = ref(false)

// ── 滚动容器引用 ──
const messageContainer = ref<HTMLElement | null>(null)

/** 从 route props.id 解析数字 conversationId */
const conversationId = computed(() => {
  const n = Number(props.id)
  return isNaN(n) || n <= 0 ? null : n
})

/** 列表项选中 → 跳转对话详情（props.id 变化已有 watch 重载） */
function onSelect(id: number): void {
  void router.push(`/conversations/${id}`)
}

/** 点「新对话」：回列表页并自动展开新对话输入框（新对话入口在列表页右栏，conversationId=null 在那发起） */
function onNewConversation(): void {
  void router.push({ path: '/conversations', query: { new: '1' } })
}

/** 加载对话详情 */
async function loadConversation(): Promise<void> {
  if (conversationId.value == null) {
    notFound.value = true
    return
  }
  loading.value = true
  notFound.value = false
  try {
    const { data, error } = await api.GET('/conversations/{id}', {
      params: { path: { id: conversationId.value } },
    })
    if (error || !data) return
    conversation.value = data
    // 后端对 failed/cancelled Turn 的数组字段（evidence/actions）与 inContext 可能返回 null
    // 而非 []，与 openapi 契约不一致。这里统一归一化，下游组件就不必各自兜底。
    context.value = data.context
    turns.value = (data.turns ?? []).map(normalizeTurn)
    await nextTick()
    scrollToBottom()
  } catch (e) {
    if (isProblem(e) && e.code === 'CONVERSATION_NOT_FOUND') {
      notFound.value = true
      await router.replace('/conversations')
    }
  } finally {
    loading.value = false
  }
}

/**
 * 归一化 Turn：后端对 failed/cancelled 轮次的 evidence/actions 返回 null、
 * inContext 也可能为 null（openapi 契约说是数组/布尔，但实际是 null）。
 * 转成 []/false 让组件按类型安全使用。这是契约与实现缺口的运行时兜底，
 * 根因在后端序列化，记入实现进度待确认区。
 */
function normalizeTurn(t: Turn): Turn {
  return {
    ...t,
    inContext: t.inContext ?? false,
    evidence: t.evidence ?? [],
    actions: t.actions ?? [],
  }
}

/** 滚到底部（新消息/流式 token 后调） */
function scrollToBottom(): void {
  const el = messageContainer.value
  if (el) el.scrollTop = el.scrollHeight
}

// ── 提交新消息 ──
async function submitMessage(): Promise<void> {
  const msg = inputMessage.value.trim()
  if (!msg) return
  if (sseTurn.state.phase !== 'idle' && sseTurn.state.phase !== 'done' && sseTurn.state.phase !== 'error') {
    return // 已在流中
  }
  // 先 reset 上一轮的 SSE 状态
  sseTurn.reset()
  inputMessage.value = ''
  // 启动 SSE 流
  await sseTurn.start(conversationId.value, msg)
  // 流外失败处理：toast 按 code 分支
  if (sseTurn.state.phase === 'error' && sseTurn.state.turnId == null) {
    handlePreStreamError()
  }
}

/** 流外失败（连接前 409/503/404/403/401）：toast 提示，不重试。
 *  401 不经 openapi-fetch middleware，需手动触发会话失效跳转。 */
function handlePreStreamError(): void {
  const err = sseTurn.state.error
  if (!err) return
  // 401 会话失效：SSE 走原生 fetch 不经 unauthMiddleware，需手动触发
  if (err.status === 401 || err.code === 'AUTHENTICATION_REQUIRED') {
    sseTurn.reset()
    auth.markSessionExpired()
    void router.push({ name: 'login' })
    return
  }
  const msg = onCode(
    { code: err.code, status: err.status, title: err.title, raw: null },
    {
      TURN_ALREADY_RUNNING: () => '当前对话还有一轮在进行',
      AI_PROVIDER_UNAVAILABLE: () => 'AI 服务暂时不可用，请稍后再试',
      CONVERSATION_NOT_FOUND: () => '对话不存在',
      CSRF_TOKEN_INVALID: () => '安全校验失败，请刷新页面重试',
    },
    () => err.title || '请求失败',
  ) ?? '请求失败'
  ui.pushToast('error', msg)
  sseTurn.reset()
}

// ── 停止生成 ──
async function stopGeneration(): Promise<void> {
  await sseTurn.cancel()
}

// ── 清除上下文 ──
async function doClearContext(): Promise<void> {
  if (conversationId.value == null) return
  try {
    const { data, error } = await api.POST('/conversations/{id}/context/clear', {
      params: { path: { id: conversationId.value } },
    })
    if (error || !data) return
    // 返回体直接覆盖本地 context（不必重拉）
    context.value = data
    ui.pushToast('info', '上下文已清除')
  } catch (e) {
    if (isProblem(e)) {
      const msg = onCode(
        e,
        {
          CONVERSATION_NOT_FOUND: () => '对话不存在',
          TURN_ALREADY_RUNNING: () => '还有一轮在跑，先停止再清',
        },
        () => '清除失败',
      ) ?? '清除失败'
      ui.pushToast('error', msg)
    }
  }
}

// ── 实时轮次的 action 卡片（SSE event:action 只给 id，取详情渲染） ──
const liveActionCards = ref<Map<number, PendingActionCard>>(new Map())

/** 加载实时 action 卡片详情（event:action 只给 id+type，需 GET /actions/{id} 取结构化字段） */
async function loadLiveActionCard(actionId: number): Promise<void> {
  if (liveActionCards.value.has(actionId)) return
  try {
    const { data, error } = await api.GET('/actions/{id}', {
      params: { path: { id: actionId } },
    })
    if (error || !data) return
    // PendingActionDetail = PendingActionCard & { targets }，可直接当 card 用
    const detail = data as PendingActionDetail
    liveActionCards.value.set(actionId, detail)
  } catch {
    // 加载失败静默
  }
}

// watch SSE actions：新 action 到达时取详情
watch(
  () => sseTurn.state.actions.length,
  () => {
    for (const a of sseTurn.state.actions) {
      if (!liveActionCards.value.has(a.pendingActionId)) {
        void loadLiveActionCard(a.pendingActionId)
      }
    }
  },
)

// ── SSE 阶段变化处理 ──
watch(
  () => sseTurn.state.phase,
  async (phase) => {
    if (phase === 'streaming') {
      // 流式进行中：token 增量时滚到底
      await nextTick()
      scrollToBottom()
    } else if (phase === 'done') {
      // 完成：重拉对话详情拿权威 turns，然后 reset SSE 状态
      await loadConversation()
      sseTurn.reset()
      liveActionCards.value.clear()
    } else if (phase === 'error' && sseTurn.state.turnId != null) {
      // 流内失败：turn 已创建，后端标 failed，重拉拿权威状态
      await loadConversation()
      sseTurn.reset()
      liveActionCards.value.clear()
    }
  },
)

// watch answerText 增量：滚到底
watch(
  () => sseTurn.state.answerText,
  async () => {
    if (sseTurn.state.phase === 'streaming' || sseTurn.state.phase === 'cancelling') {
      await nextTick()
      scrollToBottom()
    }
  },
)

// ── 派生状态 ──

/** 是否正在流式（显示实时轮次 + 停止按钮） */
const isStreaming = computed(
  () =>
    sseTurn.state.phase === 'connecting' ||
    sseTurn.state.phase === 'streaming' ||
    sseTurn.state.phase === 'cancelling',
)

/** 实时轮次是否可见（流式 + done/error 的短暂过渡） */
const showLiveTurn = computed(
  () =>
    sseTurn.state.phase === 'connecting' ||
    sseTurn.state.phase === 'streaming' ||
    sseTurn.state.phase === 'cancelling' ||
    sseTurn.state.phase === 'done' ||
    sseTurn.state.phase === 'error',
)

/** 输入框禁用（流式中） */
const inputDisabled = computed(() => isStreaming.value)

/** clearedAtTurnId（上下文清除分隔线位置） */
const clearedAtTurnId = computed(() => context.value?.clearedAtTurnId ?? null)

/** 是否有正在进行的轮次（用于禁用清除上下文） */
const hasRunningTurn = computed(() =>
  turns.value.some((t) => t.status === 'running'),
)

// ── 生命周期 ──

onMounted(async () => {
  await loadConversation()
  // 如果从 ConversationsList 新建对话导航来，SSE 正在进行——自然渲染
})

// 路由 id 变化时重新加载
watch(
  () => props.id,
  () => {
    liveActionCards.value.clear()
    void loadConversation()
  },
)

// 卸载时不 abort 流（单例状态跨导航存活，ConversationDetail 可能被重新挂载）
onUnmounted(() => {
  // 不 reset SSE 状态——如果流还在跑，下次挂载时继续渲染
})
</script>

<template>
  <div class="flex h-full overflow-hidden">
    <!-- 中栏：对话列表（常驻，activeId 高亮当前对话） -->
    <ConversationListPanel
      :active-id="conversationId"
      @select="onSelect"
      @new-conversation="onNewConversation"
    />

    <!-- 右栏：对话详情（notFound/loading/loaded 三态） -->
    <div class="flex-1 flex flex-col overflow-hidden">
      <!-- 404 回退（已 router.replace，此处兜底） -->
      <div v-if="notFound" class="flex-1 flex items-center justify-center">
        <EmptyState
          icon="fa-regular fa-circle-question"
          title="对话不存在"
          description="该对话可能已被删除"
        />
      </div>

      <div v-else-if="loading" class="flex-1 flex items-center justify-center">
        <Spin size="text-2xl" />
      </div>

      <div v-else-if="conversation" class="flex-1 flex flex-col overflow-hidden">
        <!-- 对话头：标题 + 上下文用量条 -->
        <div class="shrink-0 border-b border-border">
          <div class="px-5 py-2.5 flex items-center justify-between gap-3">
            <h1 class="text-sm font-serif font-semibold text-deep-charcoal truncate">
              {{ conversation.title }}
            </h1>
            <span class="text-[10px] text-ink-400 shrink-0">
              {{ conversation.turnCount }} 轮 · {{ formatTokenUsage(context?.usedTokens, context?.limitTokens) }}
            </span>
          </div>
          <!-- 上下文用量条 -->
          <ContextBar
            v-if="context"
            :context="context"
            :disabled="hasRunningTurn || isStreaming"
            @clear="showClearConfirm = true"
          />
        </div>
    
        <!-- 消息流 -->
        <div ref="messageContainer" class="flex-1 overflow-y-auto px-5 py-4 space-y-4">
          <!-- 压缩痕迹：早前 N 轮已压缩成摘要 -->
          <div
            v-if="context && context.compactedTurnCount > 0"
            class="flex items-center justify-center gap-2 text-[10px] text-warm-accent py-1"
          >
            <span class="h-px flex-1 bg-border max-w-[80px]" />
            <span><i class="fa-solid fa-compress text-[9px] mr-1" />早前 {{ context.compactedTurnCount }} 轮已压缩成摘要</span>
            <span class="h-px flex-1 bg-border max-w-[80px]" />
          </div>
    
          <!-- 历史轮次 -->
          <template v-for="turn in turns" :key="turn.id">
            <!-- 上下文已清除分隔线（clearedAtTurnId 处） -->
            <div
              v-if="clearedAtTurnId === turn.id"
              class="flex items-center justify-center gap-2 text-[10px] text-clay py-1"
            >
              <span class="h-px flex-1 bg-border max-w-[80px]" />
              <span><i class="fa-solid fa-eraser text-[9px] mr-1" />上下文已清除</span>
              <span class="h-px flex-1 bg-border max-w-[80px]" />
            </div>
            <TurnMessage
              :turn="turn"
              @retry="(msg) => { inputMessage = msg }"
              @decided="loadConversation"
            />
          </template>
    
          <!-- 实时轮次（SSE 流式渲染） -->
          <template v-if="showLiveTurn">
            <div class="space-y-2.5">
              <!-- 用户消息（右） -->
              <div class="flex justify-end">
                <div class="max-w-[80%] px-3.5 py-2 rounded-2xl rounded-tr-sm bg-dark-stone text-warm-white text-sm whitespace-pre-wrap break-words">
                  {{ sseTurn.state.userMessage }}
                </div>
              </div>
    
              <!-- AI 回答（左） -->
              <div class="flex justify-start">
                <div class="max-w-[85%] space-y-2">
                  <!-- 连接中 -->
                  <div v-if="sseTurn.state.phase === 'connecting'" class="flex items-center gap-2 text-xs text-ink-400">
                    <Spin size="text-sm" /> 正在思考…
                  </div>
    
                  <!-- 压缩提示（流式 event:compacted 就地插入） -->
                  <div
                    v-if="sseTurn.state.compacted"
                    class="flex items-center justify-center gap-2 text-[10px] text-warm-accent py-1"
                  >
                    <span class="h-px flex-1 bg-border max-w-[60px]" />
                    <span><i class="fa-solid fa-compress text-[9px] mr-1" />早前 {{ sseTurn.state.compacted.compactedTurnCount }} 轮已压缩成摘要</span>
                    <span class="h-px flex-1 bg-border max-w-[60px]" />
                  </div>
    
                  <!-- 流式 token 增量渲染（首事件 start 已存 turnId） -->
                  <div
                    v-if="sseTurn.state.answerText"
                    class="px-3.5 py-2.5 rounded-2xl rounded-tl-sm bg-light-beige text-dark-stone text-sm whitespace-pre-wrap break-words leading-relaxed"
                  >
                    {{ sseTurn.state.answerText }}
                    <span v-if="sseTurn.state.phase === 'streaming'" class="inline-block w-1.5 h-3.5 bg-stone-grey animate-pulse ml-0.5 align-middle" />
                  </div>
    
                  <!-- 流内错误 -->
                  <div
                    v-if="sseTurn.state.phase === 'error' && sseTurn.state.error && sseTurn.state.turnId != null"
                    class="px-3.5 py-2.5 rounded-2xl bg-danger-bg border border-danger-border text-danger-text text-xs"
                  >
                    <i class="fa-solid fa-circle-exclamation mr-1.5" />
                    {{ sseTurn.state.error.title }}
                    <p v-if="sseTurn.state.error.detail" class="mt-1 text-[11px] opacity-80">{{ sseTurn.state.error.detail }}</p>
                  </div>
    
                  <!-- 读取证据（实时） -->
                  <EvidenceList :evidence="sseTurn.state.evidence" />
    
                  <!-- 实时提案卡片（event:action 只给 id，取详情渲染） -->
                  <div v-if="sseTurn.state.actions.length > 0" class="space-y-2 pt-1">
                    <template v-for="a in sseTurn.state.actions" :key="a.pendingActionId">
                      <div v-if="liveActionCards.has(a.pendingActionId)">
                        <ApprovalCard
                          :card="liveActionCards.get(a.pendingActionId)!"
                          @decided="loadConversation"
                        />
                      </div>
                      <div v-else class="flex items-center gap-2 text-xs text-ink-400 px-2 py-1">
                        <Spin size="text-[10px]" /> 加载提案卡片…
                      </div>
                    </template>
                  </div>

                  <!-- 实时草稿提示卡（event:draft 免审批直建草稿箱） -->
                  <div v-if="sseTurn.state.drafts.length > 0" class="space-y-2 pt-1">
                    <div
                      v-for="d in sseTurn.state.drafts"
                      :key="d.draftId"
                      class="flex items-center gap-2 px-3 py-2 rounded-xl bg-sage/10 border border-sage/30 text-xs"
                    >
                      <i class="fa-regular fa-file-lines text-sage" />
                      <span class="text-ink-700">
                        草稿已创建：<span class="font-medium">{{ d.subject || '(无主题)' }}</span>
                        <span v-if="d.toPreview" class="text-ink-400"> · 收件人 {{ d.toPreview }}</span>
                      </span>
                      <RouterLink :to="{ name: 'drafts' }" class="ml-auto text-sage hover:underline shrink-0">
                        去草稿箱发送 →
                      </RouterLink>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </template>
        </div>
    
        <!-- 输入区 -->
        <div class="shrink-0 border-t border-border px-5 py-3 space-y-2">
          <!-- 停止生成按钮（running 时显示） -->
          <div v-if="isStreaming" class="flex items-center justify-between">
            <span class="text-[10px] text-ink-400 flex items-center gap-1.5">
              <Spin size="text-[10px]" />
              {{ sseTurn.state.phase === 'cancelling' ? '正在停止…' : 'AI 正在生成…' }}
            </span>
            <button
              type="button"
              class="flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-medium text-stone-grey border border-border hover:bg-light-beige transition"
              :disabled="sseTurn.state.phase === 'cancelling' || sseTurn.state.phase === 'connecting'"
              @click="stopGeneration"
            >
              <i class="fa-solid fa-stop text-[10px]" /> 停止生成
            </button>
          </div>
    
          <!-- 输入框（非流式时显示） -->
          <div v-else class="flex items-end gap-2">
            <textarea
              v-model="inputMessage"
              class="flex-1 hygge-input rounded-xl px-3 py-2 text-sm min-h-[60px] max-h-[160px] resize-y"
              placeholder="输入消息…（Enter 发送，Shift+Enter 换行）"
              :disabled="inputDisabled"
              @keydown.enter.exact.prevent="submitMessage"
            />
            <button
              type="button"
              class="flex items-center gap-1.5 px-4 py-2 rounded-xl text-xs font-medium bg-dark-stone text-warm-white hover:bg-deep-charcoal transition disabled:opacity-50 shrink-0"
              :disabled="!inputMessage.trim()"
              @click="submitMessage"
            >
              发送
            </button>
          </div>
        </div>
      </div>

      <!-- 清除上下文确认 -->
      <ConfirmDialog
        v-model="showClearConfirm"
        title="清除上下文"
        message="AI 不再记得前面说过的话，但聊天记录还在，已提出的提案不受影响。"
        confirm-text="清除"
        @confirm="doClearContext"
      />
    </div>
  </div>
</template>
