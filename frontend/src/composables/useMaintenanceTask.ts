/**
 * 维护任务轮询。sync / account-delete 返回 202 + taskId，
 * 进度经 GET /maintenance/tasks/{taskId} 查询（状态存内存，重启即失效）。
 *
 * progress 是给人看的文本（同步文件夹 / 删除账号），原样显示；
 * status 到达 succeeded / failed 终态后停轮询并触发 onDone 回调。
 * TASK_NOT_FOUND（重启后 id 失效）或网络错误时静默停止，不伪造结果。
 */
import { ref, computed, onUnmounted } from 'vue'
import { api } from '@/api/client'
import type { components } from '@/api/types.gen'

type MaintenanceTask = components['schemas']['MaintenanceTask']

/** 轮询间隔：维护任务秒级即可，太短浪费请求。 */
const POLL_INTERVAL_MS = 2000

export function useMaintenanceTask() {
  /** 当前轮询的任务状态；null=无活跃任务 */
  const activeTask = ref<MaintenanceTask | null>(null)
  let timer: number | null = null

  /** 是否正在轮询（activeTask 处于 running 态） */
  const polling = computed(() => activeTask.value?.status === 'running')

  /**
   * 开始轮询指定任务。首次立即查询，running 则每 POLL_INTERVAL_MS 轮询一次。
   * 到达终态（succeeded/failed）或出错时停止，并调用 onDone（仅终态触发）。
   */
  async function startPolling(
    taskId: string,
    onDone?: (task: MaintenanceTask) => void,
  ): Promise<void> {
    clearTimer()
    const tick = async (): Promise<void> => {
      try {
        const { data } = await api.GET('/maintenance/tasks/{taskId}', {
          params: { path: { taskId } },
        })
        if (!data) {
          clearTimer()
          return
        }
        activeTask.value = data
        if (data.status === 'running') {
          timer = window.setTimeout(tick, POLL_INTERVAL_MS)
        } else {
          // 终态：停轮询，触发回调
          clearTimer()
          onDone?.(data)
        }
      } catch {
        // TASK_NOT_FOUND（重启后 id 失效）或网络错误 → 静默停止
        clearTimer()
      }
    }
    await tick()
  }

  function clearTimer(): void {
    if (timer != null) {
      window.clearTimeout(timer)
      timer = null
    }
  }

  /** 停止轮询并清空任务状态。 */
  function stop(): void {
    clearTimer()
    activeTask.value = null
  }

  onUnmounted(() => stop())

  return { activeTask, polling, startPolling, stop }
}
