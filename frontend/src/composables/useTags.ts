/**
 * 标签列表共享缓存。标签是配置型数据（不是邮件/会话等业务数据），
 * 多组件复用：MailListPage 的标签筛选下拉 + ThreadView 里 TagEditor 的标签选择。
 *
 * 不进 Pinia——FRONTEND.md §5 只允许三个 store 存跨页面 UI 状态，
 * 业务/配置数据用 openapi-fetch 直取。这里用模块级 ref 做轻量共享缓存，
 * 与 settingsStore 缓存 guardrails 同理。
 */
import { ref } from 'vue'
import { api } from '@/api/client'
import type { Tag } from '@/utils/mail'

/** 标签列表共享缓存 */
const tags = ref<Tag[]>([])
let loaded = false

export function useTags() {
  /**
   * 拉取标签列表。已缓存则直接返回（除非 force=true）。
   * 标签 CRUD 在设置页（批3），届时需 force 刷新。
   */
  async function fetchTags(force = false): Promise<Tag[]> {
    if (loaded && !force) return tags.value
    const { data, error } = await api.GET('/tags')
    if (error || !data) return tags.value
    tags.value = data.items
    loaded = true
    return data.items
  }

  return { tags, fetchTags }
}
