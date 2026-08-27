/**
 * 邮箱账号列表共享缓存。多组件复用：
 * MailListPage 的账号切换下拉 + Drafts 的发信邮箱选择。
 *
 * 账号列表不频繁变化（CRUD 在设置页），用模块级 ref 共享缓存。
 */
import { ref } from 'vue'
import { api } from '@/api/client'
import type { MailAccount } from '@/utils/mail'

const mailAccounts = ref<MailAccount[]>([])
let loaded = false

export function useMailAccounts() {
  async function fetchMailAccounts(force = false): Promise<MailAccount[]> {
    if (loaded && !force) return mailAccounts.value
    const { data, error } = await api.GET('/mail-accounts')
    if (error || !data) return mailAccounts.value
    mailAccounts.value = data.items
    loaded = true
    return data.items
  }

  return { mailAccounts, fetchMailAccounts }
}
