import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath, URL } from 'node:url'

// 前端构建产物与 Spring Boot 同源部署（ARCHITECTURE.md §10）：
// build.outDir 直接指向后端 static 目录，npm run build 后 mvn package 即可打成一个 jar。
// 开发环境用 Vite proxy 把 /api 转发到后端 :8080，cookie/CSRF/SSE 同源透传。
export default defineConfig({
  plugins: [vue(), tailwindcss()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // /api 全部转发后端；SSE 流式响应需关掉 proxy 缓冲，否则 token 会被攒成一坨
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // 流式响应不压缩、不缓冲，确保 event: token 实时到达前端
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            if (proxyRes.headers['content-type']?.includes('text/event-stream')) {
              proxyRes.headers['cache-control'] = 'no-cache'
              proxyRes.headers['x-accel-buffering'] = 'no'
            }
          })
        },
      },
    },
  },
  build: {
    // 同源部署：产物直接写进后端 static 目录
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
    sourcemap: false,
  },
})
