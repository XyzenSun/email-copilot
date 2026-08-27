<script setup lang="ts">
/**
 * 会话详情（右栏，?thread= query 驱动）。FRONTEND.md §3.3。
 *
 * 进页面并行发两请求：
 *   1. GET /threads/{id} → 邮件摘要列表（items 升序，含 outbound，不含 spam）
 *   2. GET /threads/{id}/summary → 会话摘要（AI 现算，独立异步）
 *   若有 ?msg= 再并行 GET /messages/{msgId} 取那封完整详情。
 *   其余邮件用户展开时才 GET /messages/{id} 按需加载。
 *
 * 会话摘要三态：
 *   - 200 → 显示摘要文本
 *   - 409 THREAD_SUMMARY_DISABLED → 整块不显示（用户关了这功能，不摆报错）
 *   - 503 AI_PROVIDER_UNAVAILABLE → 显示「暂时无法生成摘要」，邮件照常可读
 *
 * 回复发信：POST /send（不经审批，用户亲手触发）。**200+status 三态**：
 *   - succeeded → toast + 刷新会话
 *   - failed → 显示 resultMessage，**保留编辑框**
 *   - indeterminate → 提示可能已发出，**绝不清空编辑框**
 *
 * 本地删除单封：DELETE /messages/{id}。已被删除提示冲突 409。
 * 会话内已无可见邮件 → 后端 404 → 前端回退列表（清 query.thread）。
 */
import { ref, watch, onMounted, nextTick } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { api } from '@/api/client'
import { isProblem, onCode } from '@/api/errors'
import { useUiStore } from '@/stores/ui'
import { useTags } from '@/composables/useTags'
import { resolveSendResult } from '@/utils/send'
import {
  formatRecipients,
  parseAddresses,
  joinAddresses,
  type MessageSummary,
  type MessageDetail,
  type ReprocessStage,
} from '@/utils/mail'
import MessageBubble from './MessageBubble.vue'
import Spin from '@/components/ui/Spin.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'

const props = defineProps<{
  threadId: number
  /** route.query.msg 定位高亮的邮件 id */
  msgId?: number
}>()

const router = useRouter()
const route = useRoute()
const ui = useUiStore()
const { tags, fetchTags } = useTags()

// ── 会话数据 ──
const threadItems = ref<MessageSummary[]>([])
const loading = ref(true)
const threadNotFound = ref(false)

// ── 会话摘要 ──
const summaryText = ref<string | null>(null)
const summaryLoading = ref(true)
/** 409 THREAD_SUMMARY_DISABLED → 整块不显示 */
const summaryDisabled = ref(false)
/** 503 AI_PROVIDER_UNAVAILABLE → 显示「暂时无法生成」 */
const summaryError = ref(false)

// ── 邮件详情（按需加载） ──
const messageDetails = ref<Map<number, MessageDetail>>(new Map())
const expandedIds = ref<Set<number>>(new Set())

// ── 回复编辑器 ──
const replyTo = ref<MessageSummary | null>(null)
const replyRecipientsText = ref('')
const replySubject = ref('')
const replyBody = ref('')
const sending = ref(false)
const savingDraft = ref(false)

// ── AI 润色 ──
const polishedText = ref<string | null>(null)
const polishLoading = ref(false)

// ── 删除确认 ──
const showDeleteConfirm = ref(false)
const deleteTargetId = ref<number | null>(null)

// ── 手动重新处理（阶段12）──
/** 正在重新处理的邮件 id（null=无），用于禁用对应气泡的按钮 + 转圈 */
const reprocessingId = ref<number | null>(null)

/** 标签更新后同步本地摘要列表中的 tags */

/** 加载会话邮件列表 */
async function loadThread(): Promise<void> {
  loading.value = true
  threadNotFound.value = false
  try {
    const { data, error } = await api.GET('/threads/{id}', {
      params: { path: { id: props.threadId } },
    })
    if (error || !data) return
    threadItems.value = data.items
    // 若有 msgId，展开那封并加载详情
    if (props.msgId) {
      const target = data.items.find((m) => m.id === props.msgId)
      if (target) {
        expandedIds.value.add(target.id)
        await loadMessageDetail(target.id)
        await nextTick()
        const el = document.querySelector(`[data-msg-id="${props.msgId}"]`)
        el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
      }
    }
  } catch (e) {
    if (isProblem(e) && e.code === 'THREAD_NOT_FOUND') {
      threadNotFound.value = true
      // 会话内已无可见邮件（全删或全 spam）→ 回退列表
      await backToList()
    }
  } finally {
    loading.value = false
  }
}

/** 加载会话摘要（独立异步，不阻塞邮件列表） */
async function loadSummary(): Promise<void> {
  summaryLoading.value = true
  summaryDisabled.value = false
  summaryError.value = false
  try {
    const { data, error } = await api.GET('/threads/{id}/summary', {
      params: { path: { id: props.threadId } },
    })
    if (error || !data) return
    summaryText.value = data.summary
  } catch (e) {
    if (isProblem(e)) {
      // 409：用户关了会话摘要功能 → 整块不显示
      if (e.code === 'THREAD_SUMMARY_DISABLED') {
        summaryDisabled.value = true
      } else if (e.code === 'AI_PROVIDER_UNAVAILABLE') {
        // 503：AI 暂不可用 → 显示提示，邮件照常可读
        summaryError.value = true
      }
      // THREAD_NOT_FOUND 等其他错误静默处理（loadThread 会处理 404）
    }
  } finally {
    summaryLoading.value = false
  }
}

/** 按需加载邮件完整详情（用户展开时调） */
async function loadMessageDetail(messageId: number): Promise<void> {
  if (messageDetails.value.has(messageId)) return
  try {
    const { data, error } = await api.GET('/messages/{id}', {
      params: { path: { id: messageId } },
    })
    if (error || !data) return
    messageDetails.value.set(messageId, data)
  } catch {
    // 加载失败静默处理（气泡会显示「加载中…」）
  }
}

/** 展开/收起邮件，展开时按需加载详情 */
async function toggleExpand(messageId: number): Promise<void> {
  if (expandedIds.value.has(messageId)) {
    expandedIds.value.delete(messageId)
  } else {
    expandedIds.value.add(messageId)
    await loadMessageDetail(messageId)
  }
}

/** 标签更新后同步本地摘要列表中的 tags */
function onTagsUpdated(messageId: number, tagIds: number[]): void {
  const item = threadItems.value.find((m) => m.id === messageId)
  if (item) {
    item.tags = tagIds
  }
  const detail = messageDetails.value.get(messageId)
  if (detail) {
    detail.tags = tagIds
  }
}

// ── 回复 ──

/** 开始回复：预填收件人与主题 */
function startReply(msg: MessageSummary): void {
  replyTo.value = msg
  // inbound → 回复发件人；outbound → 回复原收件人
  const addresses = msg.direction === 'inbound' ? [msg.fromAddress] : [...msg.recipients.to]
  replyRecipientsText.value = joinAddresses(addresses)
  const subj = msg.subject || ''
  replySubject.value = subj.toLowerCase().startsWith('re:') ? subj : `Re: ${subj}`
  replyBody.value = ''
  polishedText.value = null
}

function cancelReply(): void {
  replyTo.value = null
  replyBody.value = ''
  polishedText.value = null
}

/** 发送回复（POST /send，不经审批）。200+status 三态处理 */
async function sendReply(): Promise<void> {
  if (!replyTo.value || sending.value) return
  const recipients = parseAddresses(replyRecipientsText.value)
  if (recipients.length === 0) {
    ui.pushToast('error', '请填写至少一个收件人')
    return
  }
  sending.value = true
  try {
    const { data, error } = await api.POST('/send', {
      body: {
        fromMailAccountId: replyTo.value.mailAccountId,
        inReplyToMessageId: replyTo.value.id,
        recipients: { to: recipients, cc: [], bcc: [] },
        subject: replySubject.value,
        bodyText: replyBody.value,
      },
    })
    // problemMiddleware 对 4xx/5xx 直接 throw，这里 error 恒为 undefined
    if (error || !data) {
      ui.pushToast('error', '发送请求异常')
      return
    }
    // 200+status 三态（design §6.3：不进 error 分支、不重试）
    const outcome = resolveSendResult(data.status, data.resultMessage)
    ui.pushToast(outcome.toastType, outcome.toastMessage)
    if (outcome.clearEditor) {
      cancelReply()
    }
    if (outcome.shouldRefresh) {
      await loadThread()
    }
  } catch (e) {
    if (isProblem(e)) {
      const msg =
        onCode(
          e,
          {
            SMTP_NOT_CONFIGURED: () => '发信账号未配置 SMTP，请在设置中配置',
            INVALID_RECIPIENT_ADDRESS: () => '收件人地址无效，请检查',
            VALIDATION_FAILED: () => '参数有误：' + (e.detail ?? ''),
            MAIL_ACCOUNT_NOT_FOUND: () => '发信账号不存在',
          },
          () => '发送失败',
        ) ?? '发送失败'
      ui.pushToast('error', msg)
    } else {
      ui.pushToast('error', '发送失败，请稍后再试')
    }
  } finally {
    sending.value = false
  }
}

/** 保存回复为草稿（POST /drafts，不经审批） */
async function saveReplyAsDraft(): Promise<void> {
  if (!replyTo.value || savingDraft.value) return
  savingDraft.value = true
  try {
    const recipients = parseAddresses(replyRecipientsText.value)
    const { error } = await api.POST('/drafts', {
      body: {
        fromMailAccountId: replyTo.value.mailAccountId,
        inReplyToMessageId: replyTo.value.id,
        recipients: { to: recipients, cc: [], bcc: [] },
        subject: replySubject.value,
        bodyText: replyBody.value,
      },
    })
    if (error) return
    ui.pushToast('success', '已保存到草稿箱')
    cancelReply()
  } catch (e) {
    if (isProblem(e)) {
      ui.pushToast('error', onCode(e, {
        INVALID_RECIPIENT_ADDRESS: () => '收件人地址无效',
        VALIDATION_FAILED: () => '参数有误',
      }, () => '保存草稿失败') ?? '保存草稿失败')
    } else {
      ui.pushToast('error', '保存草稿失败')
    }
  } finally {
    savingDraft.value = false
  }
}

/** AI 润色：拿建议文本，用户点采用才写回 */
async function polishReply(): Promise<void> {
  if (!replyBody.value.trim() || polishLoading.value) return
  polishLoading.value = true
  try {
    const { data, error } = await api.POST('/drafts/polish', {
      body: { bodyText: replyBody.value },
    })
    if (error || !data) return
    polishedText.value = data.polishedText
  } catch (e) {
    if (isProblem(e)) {
      ui.pushToast('error', onCode(e, {
        AI_PROVIDER_UNAVAILABLE: () => 'AI 服务暂时不可用',
      }, () => '润色失败') ?? '润色失败')
    } else {
      ui.pushToast('error', '润色失败')
    }
  } finally {
    polishLoading.value = false
  }
}

/** 采用润色建议：写回编辑框 */
function adoptPolished(): void {
  if (polishedText.value) {
    replyBody.value = polishedText.value
    polishedText.value = null
  }
}

// ── 删除 ──

function requestDelete(messageId: number): void {
  deleteTargetId.value = messageId
  showDeleteConfirm.value = true
}

async function doDelete(): Promise<void> {
  if (deleteTargetId.value == null) return
  const targetId = deleteTargetId.value
  try {
    await api.DELETE('/messages/{id}', {
      params: { path: { id: targetId } },
    })
    ui.pushToast('success', '已删除')
    // 从本地列表移除
    threadItems.value = threadItems.value.filter((m) => m.id !== targetId)
    expandedIds.value.delete(targetId)
    messageDetails.value.delete(targetId)
    // 如果会话空了，回退列表
    if (threadItems.value.length === 0) {
      await backToList()
    }
  } catch (e) {
    if (isProblem(e)) {
      const msg = onCode(e, {
        MESSAGE_ALREADY_DELETED: () => '该邮件已被删除',
        MESSAGE_NOT_FOUND: () => '邮件不存在',
      }, () => '删除失败') ?? '删除失败'
      ui.pushToast('error', msg)
    } else {
      ui.pushToast('error', '删除失败')
    }
  } finally {
    deleteTargetId.value = null
  }
}

/**
 * 手动重新处理单封邮件某一步（阶段12）。同步 200+status 语义，不进 error 分支、不重试。
 * 响应 message 是写回后的刷新详情，覆盖缓存即可就地更新三段 + 分类 + 标签。
 */
async function reprocessMessage(messageId: number, stage: ReprocessStage): Promise<void> {
  if (reprocessingId.value === messageId) return
  reprocessingId.value = messageId
  try {
    const { data, error } = await api.POST('/messages/{id}/reprocess', {
      params: { path: { id: messageId } },
      body: { stage },
    })
    if (error || !data) {
      ui.pushToast('error', '重新处理请求异常')
      return
    }
    if (data.status === 'succeeded') {
      // 用响应里的刷新详情覆盖缓存（先失效再覆盖，design §8.4 loadMessageDetail 命中即 return）
      messageDetails.value.delete(messageId)
      messageDetails.value.set(messageId, data.message)
      const item = threadItems.value.find((m) => m.id === messageId)
      if (item) {
        item.category = data.message.category
        item.tags = data.message.tags
      }
      // 重判为 spam 后邮件应从会话视图消失（getThread 不返回 spam），reload 让其消失
      if (data.message.category === 'spam') {
        await loadThread()
        ui.pushToast('success', '已重新评分，该邮件已判为垃圾')
      } else if (stage === 'translation' && !data.message.translatedBody) {
        // 中文邮件不译，译文仍为空
        ui.pushToast('info', '该邮件为中文，无需翻译')
      } else if (stage === 'spam_judgment') {
        ui.pushToast('success', '已重新评分')
      } else {
        ui.pushToast('success', '已重新处理')
      }
    } else {
      // failed：AI 试了但没给出可用结果，产物未改
      const code = data.errorCode
      const msg = code === 'AI_STRUCTURED_OUTPUT_INVALID'
        ? 'AI 输出不合规，请稍后重试'
        : code === 'AI_PROVIDER_FAILURE'
          ? 'AI 调用失败，请稍后重试'
          : '重新处理失败'
      ui.pushToast('error', msg)
    }
  } catch (e) {
    if (isProblem(e)) {
      const msg = onCode(
        e,
        {
          MESSAGE_REPROCESS_BUSY: () => '该邮件正在被自动处理，请稍后再试',
          MESSAGE_SPAM_RECLASSIFY_FORBIDDEN: () => '垃圾邮件不可重新分类，请改走垃圾评分',
          AI_NOT_CONFIGURED: () => 'AI 未配置，无法处理',
          MESSAGE_NOT_INBOUND: () => '该邮件不可重新处理',
          VALIDATION_FAILED: () => '参数有误：' + (e.detail ?? ''),
        },
        () => '重新处理失败',
      ) ?? '重新处理失败'
      ui.pushToast('error', msg)
      // 邮件在处理期间被删 → 从列表移除（复用 doDelete 的本地清理）
      if (e.code === 'MESSAGE_NOT_FOUND') {
        threadItems.value = threadItems.value.filter((m) => m.id !== messageId)
        expandedIds.value.delete(messageId)
        messageDetails.value.delete(messageId)
        if (threadItems.value.length === 0) {
          await backToList()
        }
      }
    } else {
      ui.pushToast('error', '重新处理失败')
    }
  } finally {
    reprocessingId.value = null
  }
}

/** 回退列表：清掉 ?thread= 与 ?msg=，保留其余 query */
async function backToList(): Promise<void> {
  const rest: Record<string, string> = {}
  for (const [key, value] of Object.entries(route.query)) {
    if (key !== 'thread' && key !== 'msg' && typeof value === 'string') {
      rest[key] = value
    }
  }
  await router.replace({ query: rest })
}

// ── 生命周期 ──

onMounted(async () => {
  await Promise.all([fetchTags(), loadThread(), loadSummary()])
})

// threadId 变化时重新加载（从一封邮件跳到另一封）
watch(
  () => props.threadId,
  async () => {
    messageDetails.value.clear()
    expandedIds.value.clear()
    replyTo.value = null
    await Promise.all([loadThread(), loadSummary()])
  },
)
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden">
    <!-- 加载中 -->
    <div v-if="loading" class="flex-1 flex items-center justify-center">
      <Spin size="text-2xl" />
    </div>

    <template v-else>
      <!-- 会话摘要区（独立异步，不阻塞邮件列表） -->
      <!-- 409 THREAD_SUMMARY_DISABLED → 整块不显示 -->
      <div
        v-if="!summaryDisabled"
        class="px-5 py-3 border-b border-border bg-light-beige/50 shrink-0"
      >
        <div class="flex items-center gap-2 mb-1">
          <i class="fa-solid fa-feather text-[10px] text-ink-400" />
          <span class="text-[10px] font-mono uppercase tracking-wider text-ink-400">会话摘要</span>
        </div>
        <div v-if="summaryLoading" class="flex items-center gap-2">
          <Spin size="text-xs" />
          <span class="text-xs text-ink-400">生成中…</span>
        </div>
        <p v-else-if="summaryError" class="text-xs text-ink-400">暂时无法生成摘要，邮件照常可读</p>
        <p v-else-if="summaryText" class="text-xs text-dark-stone leading-relaxed">{{ summaryText }}</p>
      </div>

      <!-- 邮件列表（升序，inbound 靠左 / outbound 靠右） -->
      <div class="flex-1 overflow-y-auto px-5 py-4 space-y-3">
        <MessageBubble
          v-for="msg in threadItems"
          :key="msg.id"
          :message="messageDetails.get(msg.id) ?? msg"
          :expanded="expandedIds.has(msg.id)"
          :is-highlighted="msgId === msg.id"
          :all-tags="tags"
          :reprocessing="reprocessingId === msg.id"
          @toggle-expand="toggleExpand(msg.id)"
          @reply="startReply(msg)"
          @delete="requestDelete(msg.id)"
          @update-tags="(ids) => onTagsUpdated(msg.id, ids)"
          @reprocess="(stage) => reprocessMessage(msg.id, stage)"
        />

        <!-- 回复编辑器（紧跟在被回复邮件下方） -->
        <div
          v-if="replyTo"
          class="ml-8 bg-white rounded-2xl border border-border rounded-tr-sm p-4 space-y-3"
        >
          <div class="flex items-center justify-between">
            <span class="text-xs font-medium text-stone-grey">
              回复 {{ formatRecipients(replyTo.recipients) }}
            </span>
            <button
              type="button"
              class="text-xs text-ink-300 hover:text-stone-grey transition"
              @click="cancelReply"
            >
              <i class="fa-solid fa-xmark" /> 取消
            </button>
          </div>

          <!-- 收件人 -->
          <input
            v-model="replyRecipientsText"
            type="text"
            class="w-full hygge-input rounded-xl px-3 py-2 text-xs"
            placeholder="收件人（逗号分隔）"
          />

          <!-- 主题 -->
          <input
            v-model="replySubject"
            type="text"
            class="w-full hygge-input rounded-xl px-3 py-2 text-xs"
            placeholder="主题"
          />

          <!-- 正文（纯文本，禁 v-html） -->
          <textarea
            v-model="replyBody"
            class="w-full hygge-input rounded-xl px-3 py-2 text-sm min-h-[120px] resize-y"
            placeholder="正文…"
          />

          <!-- AI 润色建议（展示在编辑框旁，采用才写回） -->
          <div
            v-if="polishedText"
            class="border border-border rounded-xl p-3 bg-light-beige/50 space-y-2"
          >
            <div class="flex items-center justify-between">
              <span class="text-[10px] font-mono text-warm-accent">AI 润色建议</span>
              <div class="flex items-center gap-2">
                <button
                  type="button"
                  class="text-xs text-sage hover:underline"
                  @click="adoptPolished"
                >
                  采用
                </button>
                <button
                  type="button"
                  class="text-xs text-ink-300 hover:text-stone-grey"
                  @click="polishedText = null"
                >
                  放弃
                </button>
              </div>
            </div>
            <p class="text-xs text-dark-stone whitespace-pre-wrap break-words leading-relaxed">
              {{ polishedText }}
            </p>
          </div>

          <!-- 操作按钮 -->
          <div class="flex items-center justify-end gap-2">
            <button
              type="button"
              class="flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs text-stone-grey hover:bg-light-beige transition"
              :disabled="polishLoading || !replyBody.trim()"
              @click="polishReply"
            >
              <Spin v-if="polishLoading" size="text-xs" />
              <i v-else class="fa-solid fa-wand-magic-sparkles text-[10px]" />
              AI 润色
            </button>
            <button
              type="button"
              class="px-3 py-1.5 rounded-xl text-xs text-stone-grey hover:bg-light-beige transition"
              :disabled="savingDraft"
              @click="saveReplyAsDraft"
            >
              {{ savingDraft ? '保存中…' : '存草稿' }}
            </button>
            <button
              type="button"
              class="flex items-center gap-1.5 px-4 py-1.5 rounded-xl text-xs font-medium bg-dark-stone text-warm-white hover:bg-deep-charcoal transition disabled:opacity-50"
              :disabled="sending"
              @click="sendReply"
            >
              <Spin v-if="sending" size="text-xs" />
              <span>{{ sending ? '发送中…' : '发送' }}</span>
            </button>
          </div>
        </div>
      </div>
    </template>

    <!-- 删除确认 -->
    <ConfirmDialog
      v-model="showDeleteConfirm"
      title="删除邮件"
      message="确定删除这封邮件吗？删除后从本地彻底消失，服务器原件仍在。"
      confirm-text="删除"
      danger
      @confirm="doDelete"
    />
  </div>
</template>
