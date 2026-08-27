/**
 * 错误分支辅助。封装「按 code 分支」惯用法，避免页面里散落
 * `if (error.code === '...')` 字符串比较——既易拼错，也违背「据 code 不据 title」。
 * design.md §3.3、§6.4。
 */
import type { ProblemError } from './client'

/**
 * 判断抛出的对象是否是 ProblemError（problemMiddleware 抛出的结构化错误）。
 * 用于 catch 块区分「业务错误」与「意料外异常」。
 */
export function isProblem(e: unknown): e is ProblemError {
  return (
    typeof e === 'object' &&
    e !== null &&
    'code' in e &&
    typeof (e as { code: unknown }).code === 'string' &&
    'status' in e
  )
}

/**
 * 按 code 分支的惯用法。
 *
 *   onCode(error, {
 *     VALIDATION_FAILED: () => '参数有误',
 *     TURN_ALREADY_RUNNING: () => '当前对话还有一轮在进行',
 *   }, () => '出错了')  // fallback
 *
 * error 为 undefined（成功路径误调）或无匹配 code 时返回 fallback 结果。
 */
export function onCode<T>(
  error: ProblemError | undefined | null,
  handlers: Record<string, () => T>,
  fallback?: () => T,
): T | undefined {
  if (!error) {
    return fallback?.()
  }
  const handler = handlers[error.code]
  if (handler) {
    return handler()
  }
  return fallback?.()
}

/**
 * 取字段级校验错误（仅 VALIDATION_FAILED 等 422 响应有）。
 * 返回 field → message 映射，供表单组件定位到具体输入框。
 */
export function fieldErrors(error: ProblemError | undefined | null): Record<string, string> {
  if (!error?.errors?.length) {
    return {}
  }
  const map: Record<string, string> = {}
  for (const { field, message } of error.errors) {
    map[field] = message
  }
  return map
}
