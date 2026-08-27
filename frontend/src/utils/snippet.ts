/**
 * 搜索 snippet 关键词加粗。
 *
 * 硬约束（quality-guidelines「禁 v-html」）：snippet 与邮件正文完全由攻击者控制，
 * **绝不用 v-html 渲染**。这里返回的是**结构**（分段数组），模板里用 <mark> 逐段渲染，
 * 不产出 HTML 字符串——防止 v-html 回潮。
 * design.md §6.4、§8.4。
 */

export interface SnippetSegment {
  /** 该段文本 */
  text: string
  /** 是否是命中关键词（true → 模板渲染为 <mark>） */
  hit: boolean
}

/**
 * 把 snippet 文本按关键词切分成段，命中处标记 hit。
 *
 * @param snippet 后端返回的纯文本 snippet（已不含 HTML 标记）
 * @param keywords 用户输入的关键词（多词按空格分；前端 any/body/subject 是多词 AND）
 * @returns 分段数组，模板 v-for 渲染为 <mark> 或 <span>
 *
 * 大小写不敏感匹配；多词各自独立高亮；空关键词返回整段 hit:false。
 */
export function highlightSnippet(snippet: string, keywords: string[]): SnippetSegment[] {
  const terms = keywords
    .map((k) => k.trim())
    .filter((k) => k.length > 0)
    .map(escapeRegExp)

  if (terms.length === 0) {
    return snippet.length > 0 ? [{ text: snippet, hit: false }] : []
  }

  // 合并成一个正则，任意词命中即标记。用捕获组保留分隔
  const re = new RegExp(`(${terms.join('|')})`, 'gi')
  const segments: SnippetSegment[] = []
  let lastIndex = 0
  let match: RegExpExecArray | null

  while ((match = re.exec(snippet)) !== null) {
    // 命中前的普通文本
    if (match.index > lastIndex) {
      segments.push({ text: snippet.slice(lastIndex, match.index), hit: false })
    }
    // 命中词
    segments.push({ text: match[0], hit: true })
    lastIndex = match.index + match[0].length

    // 防御零宽匹配死循环
    if (match[0].length === 0) {
      re.lastIndex++
    }
  }

  // 尾部普通文本
  if (lastIndex < snippet.length) {
    segments.push({ text: snippet.slice(lastIndex), hit: false })
  }

  return segments
}

/** 转义正则元字符，让用户输入按字面匹配（不做正则）。 */
function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
