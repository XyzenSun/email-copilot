<script setup lang="ts">
/**
 * 对话列表页（FRONTEND.md §3.5 列表页）。
 *
 * 三栏布局：左导航（App.vue shell）+ 中栏对话列表（ConversationListPanel）+ 右栏新对话入口。
 *
 * - 中栏列表组件自包含：GET /conversations 按 updatedAt 倒序，显示 title、时间、
 *   turnCount、pendingActionCount；改名/归档/删除逻辑全在组件内。
 * - 右栏：点「新对话」展开输入框；POST /turns conversationId=null，从 event:start
 *   拿 conversationId 后 router.replace 到 /conversations/:id（ConversationDetail
 *   接管正在进行的流）。
 *
 * **新建对话不预先建行**（FRONTEND.md §3.5）：conversationId=null，流式 start 事件
 * 到达后才导航。SSE 启动逻辑（startNewConversation + watch conversationId 导航 +
 * 401 watch）保留在本页——那是新对话入口的职责，不是列表的。
 */
import { ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import { useAuthStore } from '@/stores/auth'
import { useSseTurn } from '@/composables/useSseTurn'
import Spin from '@/components/ui/Spin.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ConversationListPanel from '@/components/conversation/ConversationListPanel.vue'

const router = useRouter()
const route = useRoute()
const ui = useUiStore()
const auth = useAuthStore()
const sseTurn = useSseTurn()

// ── 新对话 ──
// 从详情页点「新对话」带 ?new=1 进入 → 自动展开输入框；展开后清掉 query，避免刷新重复展开
const showNewConversation = ref(route.query.new === '1')
if (route.query.new === '1') {
  void router.replace({ path: '/conversations' })
}
const newMessage = ref('')

/** 列表项选中 → 跳转对话详情 */
function onSelect(id: number): void {
  void router.push(`/conversations/${id}`)
}

/** 开始新对话：POST /turns conversationId=null，SSE 流式 */
async function startNewConversation(): Promise<void> {
  const msg = newMessage.value.trim()
  if (!msg) return
  // SSE 启动。从 start 事件拿 conversationId 后 watch 会导航到 /conversations/:id
  await sseTurn.start(null, msg)
}

// watch SSE 状态：拿到 conversationId 后导航（ConversationDetail 接管流）
watch(
  () => sseTurn.state.conversationId,
  (newId, oldId) => {
    // 从 null 变为数字 = 新建对话的 start 事件到达
    if (newId != null && oldId == null && sseTurn.state.phase === 'streaming') {
      showNewConversation.value = false
      newMessage.value = ''
      void router.replace(`/conversations/${newId}`)
    }
  },
)

// SSE 流外 401（会话失效）：原生 fetch 不经 unauthMiddleware，需手动跳登录
watch(
  () => sseTurn.state.phase,
  (phase) => {
    if (phase === 'error' && sseTurn.state.turnId == null) {
      const err = sseTurn.state.error
      if (err && (err.status === 401 || err.code === 'AUTHENTICATION_REQUIRED')) {
        sseTurn.reset()
        auth.markSessionExpired()
        void router.push({ name: 'login' })
      } else if (err) {
        // 其他流外错误：toast 提示
        ui.pushToast('error', err.title || '请求失败')
        sseTurn.reset()
      }
    }
  },
)
</script>

<template>
  <div class="flex h-full overflow-hidden">
    <!-- 中栏：对话列表（自包含组件，列表逻辑 + 改名/归档/删除） -->
    <ConversationListPanel
      :active-id="null"
      @select="onSelect"
      @new-conversation="showNewConversation = !showNewConversation"
    />

    <!-- 右栏：新对话入口 / 空态 -->
    <div class="flex-1 flex flex-col overflow-hidden">
      <!-- 新对话输入 -->
      <div v-if="showNewConversation" class="flex-1 flex flex-col overflow-hidden">
        <div class="px-5 py-3 border-b border-border shrink-0">
          <span class="text-xs font-medium text-stone-grey">新对话</span>
          <p class="text-[10px] text-ink-400 mt-0.5">输入消息后开始与 AI 交流，对话将在发送时创建</p>
        </div>
        <div class="flex-1 overflow-y-auto px-5 py-4">
          <!-- 流外错误（409/503 等） -->
          <div
            v-if="sseTurn.state.phase === 'error' && sseTurn.state.error"
            class="px-4 py-3 rounded-xl bg-danger-bg border border-danger-border text-danger-text text-xs"
          >
            <i class="fa-solid fa-circle-exclamation mr-1.5" />
            {{ sseTurn.state.error.title }}
            <p v-if="sseTurn.state.error.detail" class="mt-1 text-[11px] opacity-80">{{ sseTurn.state.error.detail }}</p>
          </div>
          <!-- 连接中 -->
          <div v-else-if="sseTurn.state.phase === 'connecting'" class="flex items-center gap-2 text-xs text-ink-400">
            <Spin size="text-sm" /> 正在连接 AI…
          </div>
        </div>
        <!-- 输入框 -->
        <div class="px-5 py-3 border-t border-border shrink-0 space-y-2">
          <textarea
            v-model="newMessage"
            class="w-full hygge-input rounded-xl px-3 py-2 text-sm min-h-[80px] resize-y"
            placeholder="输入消息…（Enter 发送，Shift+Enter 换行）"
            @keydown.enter.exact.prevent="startNewConversation"
          />
          <div class="flex items-center justify-between">
            <span class="text-[10px] text-ink-300">Enter 发送</span>
            <button
              type="button"
              class="flex items-center gap-1.5 px-4 py-2 rounded-xl text-xs font-medium bg-dark-stone text-warm-white hover:bg-deep-charcoal transition disabled:opacity-50"
              :disabled="!newMessage.trim() || sseTurn.state.phase === 'connecting'"
              @click="startNewConversation"
            >
              <Spin v-if="sseTurn.state.phase === 'connecting'" size="text-[10px]" />
              发送
            </button>
          </div>
        </div>
      </div>

      <!-- 空态 -->
      <EmptyState
        v-else
        icon="fa-regular fa-comments"
        title="选择一个对话"
        description="从左侧列表选择对话查看详情，或点击「新对话」开始"
      />
    </div>
  </div>
</template>
