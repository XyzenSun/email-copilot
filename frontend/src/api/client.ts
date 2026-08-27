/**
 * 类型化 API 客户端（openapi-fetch）+ 三条横切 middleware。
 *
 * 这里是前后端契约的落地点，也是三条硬约束（CSRF、401、200+status）的统一实现处——
 * 集中在 middleware 里，避免散落到每个页面。design.md §3.2、§6.2-§6.3。
 *
 *   1. csrfMiddleware  —— 写方法从 cookie 读 XSRF-TOKEN，注入 X-XSRF-TOKEN 头
 *   2. unauthMiddleware —— 401 + AUTHENTICATION_REQUIRED → 清登录态、跳登录页
 *   3. problemMiddleware —— 4xx/5xx 解析 problem+json 抛 ProblemError（200 不进此分支）
 *
 * 关键：`approve`/`send`/`test-connection` 的业务失败是 **200 + body.status**，
 * 由调用方读 body 处理，不进 error 分支、不重试（批准一次一用）。design.md §6.3。
 */
import createClient, { type Middleware } from 'openapi-fetch'
import type { paths } from './types.gen'

/** 写方法集合：只有这些需要带 CSRF 头。GET/HEAD/OPTIONS 是幂等读，不带。 */
const WRITE_METHODS = new Set(['post', 'put', 'patch', 'delete'])

/** 从非 HttpOnly 的 XSRF-TOKEN cookie 读值。后端在 GET /session 时下发。 */
function readCsrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return match?.[1] ? decodeURIComponent(match[1]) : null
}

/**
 * CSRF 双提交（quality-guidelines「CSRF」约束）：写操作须带 X-XSRF-TOKEN 头，
 * 值取自同名 cookie。同源部署，凭据随请求带（credentials: 'include'）。
 */
const csrfMiddleware: Middleware = {
  async onRequest({ request }) {
    if (WRITE_METHODS.has(request.method.toLowerCase())) {
      const token = readCsrfToken()
      if (token) {
        request.headers.set('X-XSRF-TOKEN', token)
      }
    }
    return request
  },
}

/**
 * ProblemDetail 错误（RFC 9457）。前端唯一应据 code 分支；title 是可改文案，禁用。
 * ValidationProblemDetail 额外带 errors[]（字段级校验）。
 */
export interface ProblemError {
  code: string
  title: string
  status: number
  detail?: string | null
  instance?: string | null
  /** 字段级校验错误（仅 VALIDATION_FAILED 等） */
  errors?: { field: string; message: string }[]
  /** 原始 problem 对象，备查 */
  raw: unknown
}

/**
 * 401 拦截：响应是 401 且 code === AUTHENTICATION_REQUIRED → 会话失效，跳登录页。
 * session 存内存，后端重启会触发此情况（FRONTEND.md §3.1）。
 *
 * 用注入的回调而非直接 import router/store，避免循环依赖与单测耦合。
 */
let onSessionExpired: (() => void) | null = null

/** 注入会话失效回调（由 main.ts 在 router/authStore 就绪后注入）。 */
export function configureSessionExpiredHandler(handler: () => void): void {
  onSessionExpired = handler
}

const AUTH_REQUIRED_CODE = 'AUTHENTICATION_REQUIRED'

/**
 * 401 + 会话失效拦截。其余 4xx/5xx 交 problemMiddleware 抛错，由页面按 code 处理。
 */
const unauthMiddleware: Middleware = {
  async onResponse({ request, response }) {
    if (response.status === 401) {
      // 先尝试解析 body 拿 code——但 SSE/特殊 401 可能无 JSON body，容错
      let code: string | undefined
      try {
        const cloned = response.clone()
        const body = (await cloned.json()) as { code?: string }
        code = body?.code
      } catch {
        // 无 JSON body，按纯 401 处理（访问受保护资源未登录）
        code = AUTH_REQUIRED_CODE
      }
      if (code === AUTH_REQUIRED_CODE || code === undefined) {
        // 标记请求已被处理，避免 openapi-fetch 再尝试解析 body
        void request
        onSessionExpired?.()
      }
    }
    return response
  },
}

/**
 * 错误归一：4xx/5xx 解析 problem+json 抛 ProblemError。
 * **200 不进此分支**——业务失败（approve/send/test-connection）是 200+body.status，
 * 调用方读 data 处理。这是 implement 最易写错处，见 design.md §6.3。
 */
const problemMiddleware: Middleware = {
  async onResponse({ response }) {
    if (response.ok) {
      return response // 2xx 正常通过，由 openapi-fetch 解析 body
    }
    // 4xx/5xx：解析 ProblemDetail 并抛错
    let problem: ProblemError
    try {
      const body = (await response.json()) as Record<string, unknown>
      problem = {
        code: String(body.code ?? ''),
        title: String(body.title ?? ''),
        status: response.status,
        detail: (body.detail as string | null | undefined) ?? undefined,
        instance: (body.instance as string | null | undefined) ?? undefined,
        errors: Array.isArray(body.errors)
          ? (body.errors as { field: string; message: string }[])
          : undefined,
        raw: body,
      }
    } catch {
      // 非 JSON 或网络层错误：给一个兜底 code
      problem = {
        code: 'HTTP_' + response.status,
        title: response.statusText || '请求失败',
        status: response.status,
        raw: null,
      }
    }
    throw problem
  },
}

/**
 * 类型化 API 客户端。baseUrl '/api'（openapi paths 是 /session、/messages，不含前缀）。
 *
 * 用法：
 *   const { data, error } = await api.GET('/session')
 *   if (error) { /* error 是 ProblemError，按 error.code 分支 *​/
 *   else { /* data 是 SessionInfo *​/
 *
 * 注意：approve/send/test-connection 返回 200+body.status，error 为 undefined，
 * 需读 data.status 判断业务结果，**不进 error 分支、不重试**。
 */
export const api = createClient<paths>({
  baseUrl: '/api',
  credentials: 'include', // 同源带 cookie（__Host-SESSION + XSRF-TOKEN）
})
api.use(csrfMiddleware)
api.use(unauthMiddleware)
api.use(problemMiddleware)

export type { paths }
