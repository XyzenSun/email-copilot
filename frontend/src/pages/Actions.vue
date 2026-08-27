<script setup lang="ts">
/**
 * 提案审批页（FRONTEND.md §3.6）。
 *
 * - GET /actions?status=pending（默认，按 expiresAt 升序）+ 可切
 *   approved/rejected/expired/cancelled 历史视图（按 decidedAt 倒序）。
 * - 列表项用 ApprovalCard，但列表接口**无 targets 只有 targetCount**——
 *   需按 id 各调 GET /actions/{id} 取 targets 渲染卡片（尤其是 local_delete 要展开目标列表）。
 * - 侧栏角标：审批/拒绝后调 uiStore.refreshPendingBadge() 刷新。
 * - 批量动作把目标数量做醒目提示（local_delete「将删除 N 封邮件」）。
 */
import { ref, computed, watch, onMounted } from 'vue'
import { api } from '@/api/client'
import { useUiStore } from '@/stores/ui'
import type {
  ApprovalStatus,
  PendingActionCard,
  ActionTarget,
  PendingActionDetail,
} from '@/utils/conversation'
import Spin from '@/components/ui/Spin.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ApprovalCard from '@/components/conversation/ApprovalCard.vue'

const ui = useUiStore()

// ── 筛选 ──
const statusFilter = ref<ApprovalStatus>('pending')
const page = ref(1)
const pageSize = 20
const total = ref(0)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

/** 状态 tab 配置 */
const STATUS_TABS: { value: ApprovalStatus | 'all'; label: string }[] = [
  { value: 'pending', label: '提案审批' },
  { value: 'approved', label: '已批准' },
  { value: 'rejected', label: '已拒绝' },
  { value: 'expired', label: '已过期' },
  { value: 'cancelled', label: '已取消' },
]

// ── 列表数据 ──
const items = ref<PendingActionCard[]>([])
const loading = ref(true)

/** local_delete 卡片的 targets（列表接口不给，详情接口才有） */
const targetsMap = ref<Map<number, ActionTarget[]>>(new Map())
const targetsLoading = ref<Set<number>>(new Set())

/** 加载审批列表 */
async function loadList(): Promise<void> {
  loading.value = true
  targetsMap.value.clear()
  targetsLoading.value.clear()
  try {
    const { data, error } = await api.GET('/actions', {
      params: { query: { status: statusFilter.value, page: page.value, size: pageSize } },
    })
    if (error || !data) return
    items.value = data.items
    total.value = data.total
    // 为 local_delete 卡片加载 targets（目标列表）
    for (const item of items.value) {
      if (item.actionType === 'local_delete' && item.targetCount > 0) {
        void loadTargets(item.id)
      }
    }
  } catch {
    // 401 已被全局拦截
  } finally {
    loading.value = false
  }
}

/** 加载单个卡片的 targets（GET /actions/{id} 详情才有 targets） */
async function loadTargets(actionId: number): Promise<void> {
  if (targetsMap.value.has(actionId) || targetsLoading.value.has(actionId)) return
  targetsLoading.value.add(actionId)
  try {
    const { data, error } = await api.GET('/actions/{id}', {
      params: { path: { id: actionId } },
    })
    if (error || !data) return
    const detail = data as PendingActionDetail
    targetsMap.value.set(actionId, detail.targets)
  } catch {
    // 加载失败静默（卡片仍显示 targetCount）
  } finally {
    targetsLoading.value.delete(actionId)
  }
}

/** 切状态 tab */
function selectStatus(status: ApprovalStatus): void {
  statusFilter.value = status
  page.value = 1
}

/** 审批/拒绝后刷新列表 + 角标 */
function onDecided(): void {
  void loadList()
  void ui.refreshPendingBadge()
}

// 切换筛选/翻页时重新加载
watch([statusFilter, page], () => {
  void loadList()
})

onMounted(() => {
  void loadList()
  // 进入页面时刷新角标
  void ui.refreshPendingBadge()
})
</script>

<template>
  <div class="flex-1 flex flex-col overflow-hidden">
    <!-- 状态 tab -->
    <div class="px-5 py-2.5 border-b border-border flex items-center gap-1 shrink-0">
      <button
        v-for="tab in STATUS_TABS"
        :key="tab.value"
        type="button"
        :class="[
          'px-3 py-1.5 rounded-lg text-xs font-medium transition',
          statusFilter === tab.value
            ? 'bg-dark-stone text-warm-white'
            : 'text-stone-grey hover:bg-light-beige',
        ]"
        @click="selectStatus(tab.value as ApprovalStatus)"
      >
        {{ tab.label }}
      </button>
    </div>

    <!-- 列表 -->
    <div class="flex-1 overflow-y-auto px-5 py-4">
      <div v-if="loading" class="flex items-center justify-center py-12">
        <Spin size="text-xl" />
      </div>
      <EmptyState
        v-else-if="items.length === 0"
        icon="fa-regular fa-circle-check"
        title="没有未审批的提案"
        :description="statusFilter === 'pending' ? 'AI 没有创建需要你审批的操作' : '没有历史记录'"
      />
      <div v-else class="space-y-3 max-w-3xl mx-auto">
        <ApprovalCard
          v-for="card in items"
          :key="card.id"
          :card="card"
          :targets="targetsMap.get(card.id)"
          :targets-loading="card.actionType === 'local_delete' && targetsLoading.has(card.id)"
          @decided="onDecided"
        />
      </div>
    </div>

    <!-- 分页 -->
    <div
      v-if="total > pageSize"
      class="border-t border-border px-5 py-2 flex items-center justify-between shrink-0"
    >
      <button
        type="button"
        class="text-xs text-stone-grey hover:text-deep-charcoal disabled:text-ink-300 transition"
        :disabled="page <= 1"
        @click="page--"
      >
        上一页
      </button>
      <span class="text-[10px] text-ink-400 font-mono">{{ page }} / {{ totalPages }}</span>
      <button
        type="button"
        class="text-xs text-stone-grey hover:text-deep-charcoal disabled:text-ink-300 transition"
        :disabled="page >= totalPages"
        @click="page++"
      >
        下一页
      </button>
    </div>
  </div>
</template>
