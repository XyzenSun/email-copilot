/**
 * SSE 事件流解析（纯函数）。
 *
 * 对话流式用 fetch + ReadableStream 手写（不能用 EventSource——它只支持 GET、
 * 带不了 CSRF 头）。读出的字节流经 TextDecoder 转文本后喂给这里的解析器。
 * design.md §6.1。
 *
 * 为什么是纯函数：流式解析的边界情况多（分块在行中间断开、多行 data、注释心跳、
 * 首事件必须是 start），抽成纯函数可充分单测，不依赖 DOM/Vue。
 *
 * SSE 协议要点（W3C EventSource）：
 *   - 事件以**空行**（\n\n 或 \r\n\r\n）分隔
 *   - `event: <type>`   事件类型（省略则取默认 "message"）
 *   - `data: <text>`    数据行；一个事件可有多行 data，用 \n 拼接
 *   - `: <comment>`     冒号开头是注释（心跳），忽略
 *   - `id:` / `retry:`  本项目不用（单用户、无断线续传）
 */
export interface SseEvent {
  /** 事件类型：start / token / evidence / action / error / compacted / done / message */
  event: string
  /** data 字段的原始字符串（多行已拼成单串）；JSON.parse 由调用方做 */
  data: string
}

/**
 * 从累积的文本缓冲里解析出已完整的事件。
 *
 * @param buffer 累积的全部文本（含可能未结束的最后一段）
 * @param startIndex 从 buffer 的哪个位置开始扫描（上次解析停留处）
 * @returns { events, nextIndex } —— 解析出的完整事件 + 下次应从哪继续
 *          （未结束的最后一段保留在 buffer 里等下次拼）
 */
export function parseSseEvents(buffer: string, startIndex = 0): { events: SseEvent[]; nextIndex: number } {
  const events: SseEvent[] = []
  let cursor = startIndex

  while (cursor < buffer.length) {
    // 找下一个事件分隔（空行）。兼容 \n\n、\r\n\r\n、以及行尾混杂
    const sepIndex = findEventBoundary(buffer, cursor)
    if (sepIndex === -1) {
      // 没找到完整空行 → 剩余是不完整事件，等下次
      break
    }

    const rawEvent = buffer.slice(cursor, sepIndex.start)
    cursor = sepIndex.end // 跳过空行，指向下一个事件起点

    const parsed = parseSingleEvent(rawEvent)
    if (parsed) {
      events.push(parsed)
    }
    // parsed 为 null（纯注释/空事件）则跳过，不产出
  }

  return { events, nextIndex: cursor }
}

/** 在 buffer[from..] 里找第一个事件边界（空行），返回边界起止位置；找不到返回 -1。 */
function findEventBoundary(buffer: string, from: number): { start: number; end: number } | -1 {
  for (let i = from; i < buffer.length; i++) {
    if (buffer[i] === '\n') {
      // 检查这是否是空行（\n 紧跟 \n，或 \n 前是 \r 且 \r 前是 \n）
      const next = buffer[i + 1]
      if (next === '\n') {
        return { start: i, end: i + 2 }
      }
      if (next === '\r' && buffer[i + 2] === '\n') {
        return { start: i, end: i + 3 }
      }
    }
    if (buffer[i] === '\r' && buffer[i + 1] === '\n') {
      const next = buffer[i + 2]
      if (next === '\n') {
        return { start: i, end: i + 3 }
      }
      if (next === '\r' && buffer[i + 3] === '\n') {
        return { start: i, end: i + 4 }
      }
    }
  }
  return -1
}

/** 解析单个事件的原始文本块（不含结尾空行）为 SseEvent；纯注释或无 data 返回 null。 */
function parseSingleEvent(raw: string): SseEvent | null {
  let event = 'message' // SSE 默认事件类型
  const dataLines: string[] = []

  for (const line of raw.split(/\r?\n/)) {
    if (line.length === 0) continue // 事件内空行不应出现，防御
    if (line[0] === ':') continue // 注释/心跳，忽略

    const colon = line.indexOf(':')
    // SSE: field 后冒号后若紧跟空格要跳过那个空格
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) {
      value = value.slice(1)
    }

    if (field === 'event') {
      event = value
    } else if (field === 'data') {
      dataLines.push(value)
    }
    // id / retry 等字段本项目不处理
  }

  // 无 data 的事件（如纯心跳）不产出——后端所有事件都带 data
  if (dataLines.length === 0 && event === 'message') {
    return null
  }

  return {
    event,
    data: dataLines.join('\n'),
  }
}
