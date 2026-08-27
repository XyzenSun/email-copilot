/**
 * 通用格式化工具。纯函数，可单测。
 * 时间一律按后端契约 ISO 8601 带时区（API.md §1）。
 */

/**
 * 格式化时间为「YYYY-MM-DD HH:mm」。后端时间是带时区 ISO 串，
 * 直接 new Date 解析（浏览器按本地时区显示，单用户自用可接受）。
 */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (isNaN(d.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 相对时间：1 小时内显示「N 分钟前」，否则落回 formatDateTime。单用户自用，不过度细化。 */
export function formatRelative(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (isNaN(d.getTime())) return ''
  const diffMs = Date.now() - d.getTime()
  const diffMin = Math.floor(diffMs / 60000)
  if (diffMin < 1) return '刚刚'
  if (diffMin < 60) return `${diffMin} 分钟前`
  return formatDateTime(iso)
}

/** 格式化数量：>1000 显示「1.2k」，避免长数字挤占列表项。 */
export function formatCount(n: number | null | undefined): string {
  if (n == null) return '0'
  if (n < 1000) return String(n)
  if (n < 1_000_000) return (n / 1000).toFixed(1).replace(/\.0$/, '') + 'k'
  return (n / 1_000_000).toFixed(1).replace(/\.0$/, '') + 'M'
}

/**
 * 格式化 token 用量条：「已用 12,345 / 200,000（6%）」。
 * limitTokens 从接口取，不写死（用户可能换了窗口不同的模型）。
 */
export function formatTokenUsage(used: number | null | undefined, limit: number | null | undefined): string {
  const u = used ?? 0
  const l = limit ?? 0
  if (l <= 0) return formatCount(u)
  const pct = Math.round((u / l) * 100)
  return `${formatCount(u)} / ${formatCount(l)}（${pct}%）`
}
