/**
 * 对话 SSE 流式 composable（design §6.1、conversation-ai.md §3 SSE 事件协议）。
 *
 * 为什么用原生 fetch 而非 openapi-fetch / EventSource：
 *   - EventSource 只支持 GET、带不了 CSRF 头（CSRF 双提交要 X-XSRF-TOKEN header）。
 *   - openapi-fetch 把 text/event-stream 当普通文本 body 处理，不适合增量渲染。
 *   用原生 fetch + ReadableStream + parseSseEvents 增量解析。
 *
 * 为什么是模块级单例状态（reactive 而非 ref in setup）：
 *   新建对话时 conversationId=null，从 event:start 拿到 conversationId 后要
 *   router.replace 到 /conversations/:id。组件卸载会丢失 ref 状态，流就断了。
 *   单例状态跨导航存活——ConversationsList 启动流 → 导航 → ConversationDetail
 *   接管正在进行的流继续渲染。
 *
 * 关键约束（硬约束 3）：
 *   - **首事件 start 先存 turnId 再渲染 token**——停止按钮要用 turnId。
 *   - event:action 只给 id，不取流内 AI 文本（硬约束 1）。
 *   - 流外失败走 HTTP 状态码（409/503/404），流内失败走 event:error。
 */
import { reactive } from 'vue'
import { parseSseEvents } from '@/utils/sse'
import type {
  ReadEvidence,
  ActionType,
  TurnStatus,
  SseStartEvent,
  SseTokenEvent,
  SseActionEvent,
  SseErrorEvent,
  SseCompactedEvent,
  SseDoneEvent,
  SseDraftEvent,
} from '@/utils/conversation'

/** 流式阶段 */
export type SsePhase = 'idle' | 'connecting' | 'streaming' | 'cancelling' | 'done' | 'error'

/** 流内 action 事件：只给 id+类型，组件据此调 GET /actions/{id} 取详情 */
export interface SseActionRef {
  pendingActionId: number
  actionType: ActionType
}

/** 流内 draft 事件：草稿免审批直建，给 id+主题+收件人预览渲染提示卡 */
export interface SseDraftRef {
  draftId: number
  subject: string | null
  toPreview: string | null
}

/** 错误结构（流外 problem+json 或流内 event:error） */
export interface SseTurnError {
  status: number
  code: string
  title: string
  detail?: string | null
}

/** 单例流式状态。跨组件/导航存活。 */
export interface SseTurnState {
  phase: SsePhase
  /** 从 start 事件拿到的 turnId（停止按钮要用，必须先存再渲染 token） */
  turnId: number | null
  /** 从 start 事件拿到的 conversationId（新建对话时用于 router.replace） */
  conversationId: number | null
  /** 从 start 事件拿到的对话标题（新建对话时用于侧栏插行） */
  title: string | null
  /** 用户提交的消息 */
  userMessage: string
  /** 增量累积的 AI 回答文本 */
  answerText: string
  /** 本轮读取证据（event:evidence 由代码写入，非模型自述） */
  evidence: ReadEvidence[]
  /** 本轮创建的提案（event:action 只给 id+类型） */
  actions: SseActionRef[]
  /** 本轮创建的草稿（event:draft 免审批直建草稿箱，前端渲染"草稿已创建"提示卡） */
  drafts: SseDraftRef[]
  /** 压缩信息（event:compacted） */
  compacted: { compactedTurnCount: number; usedTokens: number } | null
  /** done 事件的终态 */
  doneStatus: TurnStatus | null
  /** done 事件的 usedTokens（刷新用量条） */
  usedTokens: number | null
  /** 错误（流外失败或流内 error 事件） */
  error: SseTurnError | null
}

// ── 模块级单例状态 ──
const state = reactive<SseTurnState>({
  phase: 'idle',
  turnId: null,
  conversationId: null,
  title: null,
  userMessage: '',
  answerText: '',
  evidence: [],
  actions: [],
  drafts: [],
  compacted: null,
  doneStatus: null,
  usedTokens: null,
  error: null,
})

/** 当前流的 AbortController（组件卸载时可 abort，但单例模式一般不卸载） */
let abortController: AbortController | null = null

/** 从非 HttpOnly 的 XSRF-TOKEN cookie 读值（与 client.ts 的 readCsrfToken 同逻辑，此处不复用是因为 SSE 走原生 fetch 不经 middleware） */
function readCsrfTokenFromCookie(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return match?.[1] ? decodeURIComponent(match[1]) : null
}

/** 重置到 idle（开始新轮次前调） */
function resetState(): void {
  state.phase = 'idle'
  state.turnId = null
  state.conversationId = null
  state.title = null
  state.userMessage = ''
  state.answerText = ''
  state.evidence = []
  state.actions = []
  state.drafts = []
  state.compacted = null
  state.doneStatus = null
  state.usedTokens = null
  state.error = null
}

/**
 * 解析 problem+json 响应体为 SseTurnError。
 * 流外失败（409/503/404/403/401）走这里。
 */
async function parseProblemResponse(res: Response): Promise<SseTurnError> {
  try {
    const body = (await res.json()) as Record<string, unknown>
    return {
      status: res.status,
      code: String(body.code ?? ''),
      title: String(body.title ?? ''),
      detail: (body.detail as string | null | undefined) ?? undefined,
    }
  } catch {
    return {
      status: res.status,
      code: 'HTTP_' + res.status,
      title: res.statusText || '请求失败',
    }
  }
}

/**
 * 启动一轮对话（POST /api/turns，SSE 流式）。
 *
 * @param conversationId 对话 id；null 表示新建对话（在同一次请求里创建并跑第一轮）
 * @param message 用户消息
 */
async function start(conversationId: number | null, message: string): Promise<void> {
  if (state.phase === 'connecting' || state.phase === 'streaming' || state.phase === 'cancelling') {
    return // 已在流中，防重复提交
  }
  resetState()
  state.userMessage = message
  state.phase = 'connecting'

  abortController = new AbortController()
  try {
    const res = await fetch('/api/turns', {
      method: 'POST',
      credentials: 'include',
      signal: abortController.signal,
      headers: {
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': readCsrfTokenFromCookie() ?? '',
      },
      body: JSON.stringify({
        conversationId,
        // 后端 TurnCreateRequest 字段名是 userMessage（与 openapi TurnRequest 一致，已对齐）
        userMessage: message,
      } satisfies {
        conversationId: number | null
        userMessage: string
      }),
    })

    // 流外失败：非 2xx → 解析 problem+json 设 error
    if (!res.ok) {
      state.error = await parseProblemResponse(res)
      state.phase = 'error'
      return
    }

    // 200 → 读 SSE 流
    state.phase = 'streaming'
    await readStream(res)
  } catch (err) {
    // abort 或网络层错误
    if (err instanceof DOMException && err.name === 'AbortError') {
      // 主动 abort（一般由 cancel 触发或组件卸载），不设 error
      return
    }
    state.error = {
      status: 0,
      code: 'NETWORK_ERROR',
      title: '网络错误',
      detail: '无法连接服务器',
    }
    state.phase = 'error'
  } finally {
    abortController = null
  }
}

/** 读 ReadableStream，增量解析 SSE 事件 */
async function readStream(res: Response): Promise<void> {
  const reader = res.body?.getReader()
  if (!reader) {
    state.error = { status: 0, code: 'NO_STREAM', title: '响应体为空' }
    state.phase = 'error'
    return
  }
  const decoder = new TextDecoder()
  let buffer = ''
  let lastIndex = 0

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const { events, nextIndex } = parseSseEvents(buffer, lastIndex)
    lastIndex = nextIndex
    for (const ev of events) {
      handleEvent(ev.event, ev.data)
      // done 或 error 事件后停止读流
      if (state.phase === 'done' || state.phase === 'error') {
        // cancel reader 以退出循环
        try { await reader.cancel() } catch { /* 流已关闭 */ }
        return
      }
    }
  }
  // 流自然结束但没收到 done 事件（服务端关闭连接）
  if (state.phase === 'streaming') {
    state.phase = 'done'
    state.doneStatus = state.doneStatus ?? 'completed'
  }
}

/** 处理单个 SSE 事件 */
function handleEvent(event: string, data: string): void {
  switch (event) {
    case 'start': {
      // **必须最先处理**：存 turnId 再渲染 token——停止按钮要用
      const d = JSON.parse(data) as SseStartEvent
      state.turnId = d.turnId
      state.conversationId = d.conversationId
      state.title = d.title
      break
    }
    case 'token': {
      const d = JSON.parse(data) as SseTokenEvent
      state.answerText += d.text
      break
    }
    case 'evidence': {
      // 由代码写入，非模型自述
      const d = JSON.parse(data) as ReadEvidence
      state.evidence.push(d)
      break
    }
    case 'action': {
      // 只给 id+类型，不取流内 AI 文本（硬约束 1）
      const d = JSON.parse(data) as SseActionEvent
      state.actions.push({ pendingActionId: d.pendingActionId, actionType: d.actionType })
      break
    }
    case 'draft': {
      // 草稿免审批直建草稿箱（2026-08-25）：给 id+主题+收件人预览，前端渲染提示卡
      const d = JSON.parse(data) as SseDraftEvent
      state.drafts.push({ draftId: d.draftId, subject: d.subject, toPreview: d.toPreview ?? null })
      break
    }
    case 'compacted': {
      const d = JSON.parse(data) as SseCompactedEvent
      state.compacted = { compactedTurnCount: d.compactedTurnCount, usedTokens: d.usedTokens }
      break
    }
    case 'error': {
      // 流内失败：同 ProblemDetail 结构
      const d = JSON.parse(data) as SseErrorEvent
      state.error = { status: d.status, code: d.code, title: d.title, detail: d.detail }
      state.phase = 'error'
      break
    }
    case 'done': {
      const d = JSON.parse(data) as SseDoneEvent
      state.doneStatus = d.status
      state.usedTokens = d.usedTokens
      state.phase = 'done'
      break
    }
    default:
      // 未知事件类型，忽略（前向兼容）
      break
  }
}

/**
 * 停止生成。POST /api/turns/{id}/cancel → 204，不等真停。
 * 已生成的文字会保存，done 事件的 status 为 cancelled。
 * 关闭标签页不等于停止——后端感知不到，会继续跑完。
 */
async function cancel(): Promise<void> {
  if (state.turnId == null) return
  if (state.phase !== 'streaming' && state.phase !== 'cancelling') return
  state.phase = 'cancelling'
  try {
    // cancel 是写操作，需带 CSRF 头——用原生 fetch（与 start 同路径）
    const res = await fetch(`/api/turns/${state.turnId}/cancel`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'X-XSRF-TOKEN': readCsrfTokenFromCookie() ?? '' },
    })
    // 204=已记录取消请求；409=已经结束了（TURN_NOT_RUNNING）；都静默处理
    // 实际停止由流内 done(cancelled) 事件驱动
    void res
  } catch {
    // cancel 失败静默——流仍在跑，用户可再点
  }
}

/** 手动重置到 idle（用于「重新提问」） */
function reset(): void {
  resetState()
}

export function useSseTurn() {
  return {
    state,
    start,
    cancel,
    reset,
  }
}
