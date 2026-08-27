<script setup lang="ts">
/**
 * 实时日志面板（侧栏左下角，系统状态上方）。
 *
 * 设计目标（Xyzen 2026-08-25）：前端发请求，后端起一个 SSE 流，将此时刻以后的
 * 日志实时转发到前端；页面不提供筛选，只展示。
 *
 * 当前状态：后端日志流接口尚未实现，本面板为 UI 占位。接口就绪后接入方式：
 *   - 原生 fetch + ReadableStream + parseSseEvents（复用 utils/sse.ts），不用 EventSource
 *     ——SSE 需带 CSRF 头（X-XSRF-TOKEN 双提交），EventSource 带不了自定义头。
 *   - 与 useSseTurn 同模式：模块级单例状态、start/cancel、401 手动跳登录。
 *   - 日志行用 {{ }} 文本插值，禁 v-html（日志含任意字段，不冒险）。
 */
</script>

<template>
  <div class="mt-3 p-3 rounded-2xl bg-white border border-border space-y-2 shadow-sm shrink-0">
    <div class="flex items-center justify-between">
      <span class="font-serif text-[11px] font-semibold text-deep-charcoal flex items-center gap-1.5">
        <i class="fa-solid fa-terminal text-[10px] text-ink-400" /> 实时日志
      </span>
      <span class="text-[9px] font-mono text-ink-300">待接入</span>
    </div>
    <p class="text-[10px] text-ink-400 leading-relaxed">
      连接后实时展示此时刻起的系统日志。
    </p>
    <button
      type="button"
      class="w-full flex items-center justify-center gap-1.5 px-2 py-1.5 rounded-xl text-[10px] font-medium text-ink-400 bg-light-beige border border-border cursor-not-allowed"
      disabled
      title="后端日志 SSE 接口尚未实现"
    >
      <i class="fa-solid fa-play text-[8px]" /> 开始监听
    </button>
  </div>
</template>
