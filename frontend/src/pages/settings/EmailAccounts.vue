<script setup lang="ts">
/**
 * 邮箱账号设置。FRONTEND.md §3.7。
 *
 * 收信（IMAP）与发信（SMTP）通道配置、凭据管理、测试连接、手动同步、停用与删除。
 *
 * 安全硬约束：
 *   - 凭据永不回显：secrets 只显布尔「已配置/未配置」，密码输入框 type=password 提交后清空。
 *   - 测试连接 200+ok:false 不进 error 分支：连不上是业务结果不是请求错误。
 *   - 删账号手输邮箱核对（requireTextInput）+ 显示 messageCount。
 *   - 启用通道前必须先配好凭据，否则 422 SECRET_REQUIRED（界面上把填密码排在开关前）。
 *   - 停用与删除是两个不同动作：停用=两开关全关（邮件照常可读）；删除=物理销毁（只对已停用账号）。
 */
import { ref, computed, onMounted } from 'vue'
import { api } from '@/api/client'
import { isProblem, onCode, fieldErrors } from '@/api/errors'
import { useUiStore } from '@/stores/ui'
import { useMailAccounts } from '@/composables/useMailAccounts'
import { useMaintenanceTask } from '@/composables/useMaintenanceTask'
import { formatCount, formatDateTime } from '@/utils/format'
import type { components } from '@/api/types.gen'
import Spin from '@/components/ui/Spin.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import Modal from '@/components/ui/Modal.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'

type MailAccount = components['schemas']['MailAccount']
type MailAccountCreateRequest = components['schemas']['MailAccountCreateRequest']
type MailAccountUpdateRequest = components['schemas']['MailAccountUpdateRequest']
type SecretType = components['schemas']['SecretType']
type TestConnectionResult = components['schemas']['TestConnectionResult']

const ui = useUiStore()
const { mailAccounts, fetchMailAccounts } = useMailAccounts()
const { activeTask, startPolling } = useMaintenanceTask()

// ── 列表 ──
const loading = ref(true)

// ── 新建/编辑表单 ──
const showFormModal = ref(false)
const isEditMode = ref(false)
const editingAccount = ref<MailAccount | null>(null)
const saving = ref(false)
const formErrors = ref<Record<string, string>>({})

// 表单字段。number 字段用 string ref + 手动 parse，避免 v-model.number 空值类型问题。
const fEmailAddress = ref('')
const fDisplayName = ref('')
const fImapHost = ref('')
const fImapPort = ref('993')
const fImapUsername = ref('')
const fImapFolders = ref('')
const fImapPassword = ref('')
const fImapEnabled = ref(false)
const fSmtpHost = ref('')
const fSmtpPort = ref('587')
const fSmtpUsername = ref('')
const fSmtpPassword = ref('')
const fSmtpEnabled = ref(false)

// ── 测试连接 ──
const testing = ref<string | null>(null) // `${accountId}-${channel}`

// ── 同步/删除任务 ──
const syncingId = ref<number | null>(null)
const taskAccountId = ref<number | null>(null)
const taskKind = ref<'sync' | 'delete' | null>(null)

// ── 删除确认 ──
const showDeleteConfirm = ref(false)
const deleteTarget = ref<MailAccount | null>(null)
const deletingId = ref<number | null>(null)

/** 账号是否已全部停用（允许删除的前提） */
function isStopped(acc: MailAccount): boolean {
  return !acc.imapEnabled && !acc.smtpEnabled
}

/** 测试按钮是否忙碌（任一测试进行中时全局禁用其余） */
const anyTesting = computed(() => testing.value !== null)

// ── 表单操作 ──

function resetForm(): void {
  fEmailAddress.value = ''
  fDisplayName.value = ''
  fImapHost.value = ''
  fImapPort.value = '993'
  fImapUsername.value = ''
  fImapFolders.value = ''
  fImapPassword.value = ''
  fImapEnabled.value = false
  fSmtpHost.value = ''
  fSmtpPort.value = '587'
  fSmtpUsername.value = ''
  fSmtpPassword.value = ''
  fSmtpEnabled.value = false
  formErrors.value = {}
}

function populateForm(acc: MailAccount): void {
  editingAccount.value = acc
  fEmailAddress.value = acc.emailAddress
  fDisplayName.value = acc.displayName
  fImapHost.value = acc.imapHost ?? ''
  fImapPort.value = acc.imapPort != null ? String(acc.imapPort) : ''
  fImapUsername.value = acc.imapUsername ?? ''
  fImapFolders.value = acc.imapFolders ? acc.imapFolders.join(', ') : ''
  // 密码永不清空回填——凭据永不回显
  fImapPassword.value = ''
  fImapEnabled.value = acc.imapEnabled
  fSmtpHost.value = acc.smtpHost ?? ''
  fSmtpPort.value = acc.smtpPort != null ? String(acc.smtpPort) : ''
  fSmtpUsername.value = acc.smtpUsername ?? ''
  fSmtpPassword.value = ''
  fSmtpEnabled.value = acc.smtpEnabled
  formErrors.value = {}
}

function openCreateModal(): void {
  resetForm()
  editingAccount.value = null
  isEditMode.value = false
  showFormModal.value = true
}

function openEditModal(acc: MailAccount): void {
  populateForm(acc)
  isEditMode.value = true
  showFormModal.value = true
}

/** 逗号分隔文本 → 文件夹名数组 */
function parseFolders(text: string): string[] {
  return text
    .split(/[,\s]+/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
}

/** 空字符串 → null（后端 nullable 字段语义：null=未配置） */
function emptyToNull(s: string): string | null {
  const trimmed = s.trim()
  return trimmed === '' ? null : trimmed
}

/** 端口文本 → number | null */
function parsePort(text: string): number | null {
  const trimmed = text.trim()
  if (trimmed === '') return null
  const n = Number(trimmed)
  return Number.isInteger(n) && n > 0 && n < 65536 ? n : null
}

const SECRET_LABELS: Record<string, string> = {
  'imap-password': 'IMAP 密码',
  'smtp-password': 'SMTP 密码',
}

function secretLabel(type: SecretType): string {
  return SECRET_LABELS[type] ?? '凭据'
}

/**
 * 写入单条凭据（PUT secrets）。空值跳过（不改动已有凭据）。
 * 返回是否成功——失败时已弹 toast，调用方据此决定是否继续。
 */
async function putSecretIfPresent(
  accountId: number,
  type: SecretType,
  value: string,
): Promise<boolean> {
  if (!value) return true // 无新值，不改
  try {
    await api.PUT('/mail-accounts/{id}/secrets/{type}', {
      params: { path: { id: accountId, type } },
      body: { value },
    })
    return true
  } catch (e) {
    if (isProblem(e)) {
      ui.pushToast(
        'error',
        onCode(
          e,
          { VALIDATION_FAILED: () => `${secretLabel(type)} 格式有误` },
          () => `${secretLabel(type)} 写入失败`,
        ) ?? `${secretLabel(type)} 写入失败`,
      )
    } else {
      ui.pushToast('error', `${secretLabel(type)} 写入失败`)
    }
    return false
  }
}

/**
 * 客户端预检：启用通道前必须先配好该通道凭据。
 * 凭据「已配」= 密码输入框有新值 或 账号 secrets 已显 true。
 * 后端亦会 422 兜底，但前端预检省一次往返、UX 更好。
 */
function checkEnablePreconditions(): boolean {
  if (!isEditMode.value) return true // 新建始终 disabled，无需检查
  const acc = editingAccount.value
  if (!acc) return true

  if (fImapEnabled.value) {
    const hasCred = !!fImapPassword.value || acc.secrets.imapPassword
    if (!hasCred) {
      ui.pushToast('error', '启用 IMAP 收信前请先填写 IMAP 密码')
      return false
    }
  }
  if (fSmtpEnabled.value) {
    const hasCred = !!fSmtpPassword.value || acc.secrets.smtpPassword
    if (!hasCred) {
      ui.pushToast('error', '启用 SMTP 发信前请先填写 SMTP 密码')
      return false
    }
  }
  return true
}

/** 保存表单（新建或编辑） */
async function saveForm(): Promise<void> {
  if (saving.value) return
  if (!fEmailAddress.value.trim() || !fDisplayName.value.trim()) {
    ui.pushToast('error', '邮箱地址和显示名为必填项')
    return
  }
  if (!checkEnablePreconditions()) return

  saving.value = true
  formErrors.value = {}
  try {
    if (isEditMode.value && editingAccount.value) {
      await doUpdate(editingAccount.value.id)
    } else {
      await doCreate()
    }
  } catch (e) {
    if (isProblem(e)) {
      formErrors.value = fieldErrors(e)
      ui.pushToast(
        'error',
        onCode(
          e,
          {
            VALIDATION_FAILED: () => '部分字段有误，请检查标红项',
            MAIL_ACCOUNT_ADDRESS_TAKEN: () => '该邮箱地址已存在',
            MAIL_ACCOUNT_NOT_FOUND: () => '账号不存在',
            SECRET_REQUIRED: () => '启用通道前必须先配置对应凭据',
          },
          () => '保存失败',
        ) ?? '保存失败',
      )
    } else {
      ui.pushToast('error', '保存失败，请稍后再试')
    }
  } finally {
    saving.value = false
  }
}

/** 新建账号：POST 配置 → PUT 凭据 → 刷新列表 */
async function doCreate(): Promise<void> {
  const body: MailAccountCreateRequest = {
    emailAddress: fEmailAddress.value.trim(),
    displayName: fDisplayName.value.trim(),
    imapHost: emptyToNull(fImapHost.value),
    imapPort: parsePort(fImapPort.value),
    imapUsername: emptyToNull(fImapUsername.value),
    imapFolders: fImapFolders.value.trim() ? parseFolders(fImapFolders.value) : null,
    imapEnabled: false, // 新建始终停用，启用在编辑里做
    smtpHost: emptyToNull(fSmtpHost.value),
    smtpPort: parsePort(fSmtpPort.value),
    smtpUsername: emptyToNull(fSmtpUsername.value),
    smtpEnabled: false,
  }
  const { data, error } = await api.POST('/mail-accounts', { body })
  if (error || !data) return

  // POST 成功后写入凭据（若填了）
  const id = data.id
  await putSecretIfPresent(id, 'imap-password', fImapPassword.value)
  await putSecretIfPresent(id, 'smtp-password', fSmtpPassword.value)

  await fetchMailAccounts(true)
  showFormModal.value = false
  ui.pushToast('success', '账号已创建')
}

/** 编辑账号：PATCH 配置 → PUT 凭据 → 刷新列表 */
async function doUpdate(id: number): Promise<void> {
  const body: MailAccountUpdateRequest = {
    emailAddress: fEmailAddress.value.trim(),
    displayName: fDisplayName.value.trim(),
    imapHost: emptyToNull(fImapHost.value),
    imapPort: parsePort(fImapPort.value),
    imapUsername: emptyToNull(fImapUsername.value),
    imapFolders: fImapFolders.value.trim() ? parseFolders(fImapFolders.value) : null,
    imapEnabled: fImapEnabled.value,
    smtpHost: emptyToNull(fSmtpHost.value),
    smtpPort: parsePort(fSmtpPort.value),
    smtpUsername: emptyToNull(fSmtpUsername.value),
    smtpEnabled: fSmtpEnabled.value,
  }
  const { data, error } = await api.PATCH('/mail-accounts/{id}', {
    params: { path: { id } },
    body,
  })
  if (error || !data) return

  // PATCH 成功后写入凭据（若填了）
  await putSecretIfPresent(id, 'imap-password', fImapPassword.value)
  await putSecretIfPresent(id, 'smtp-password', fSmtpPassword.value)

  await fetchMailAccounts(true)
  showFormModal.value = false
  ui.pushToast('success', '账号已更新')
}

// ── 测试连接 ──

/**
 * 测试 IMAP 或 SMTP 连接。POST test-connection → 200 + { ok, message }。
 * **ok:false 也是 200**，读 message 显示诊断，不进 error 分支、不重试。
 * 422 SECRET_REQUIRED（该通道无凭据）走 catch。
 */
async function testConnection(acc: MailAccount, channel: 'imap' | 'smtp'): Promise<void> {
  if (anyTesting.value) return
  const key = `${acc.id}-${channel}`
  testing.value = key
  try {
    const { data, error } = await api.POST('/mail-accounts/{id}/test-connection', {
      params: { path: { id: acc.id } },
      body: { channel },
    })
    if (error || !data) return
    const label = channel === 'imap' ? 'IMAP' : 'SMTP'
    showTestResult(label, data)
  } catch (e) {
    if (isProblem(e)) {
      ui.pushToast(
        'error',
        onCode(
          e,
          {
            SECRET_REQUIRED: () => `${channel.toUpperCase()} 未配置凭据，无从测试`,
            MAIL_ACCOUNT_NOT_FOUND: () => '账号不存在',
          },
          () => '测试失败',
        ) ?? '测试失败',
      )
    } else {
      ui.pushToast('error', '测试失败，请稍后再试')
    }
  } finally {
    testing.value = null
  }
}

function showTestResult(label: string, result: TestConnectionResult): void {
  if (result.ok) {
    ui.pushToast('success', `${label} 连接成功`)
  } else {
    // ok:false 是 200，是业务结果不是错误——诊断文本可能含服务器回显用户名，后端已过滤
    ui.pushToast('error', `${label} 连接失败：${result.message}`)
  }
}

// ── 手动同步 ──

/** POST sync → 202 + taskId → 轮询 GET maintenance/tasks/{taskId} */
async function syncAccount(acc: MailAccount): Promise<void> {
  if (syncingId.value !== null || taskAccountId.value !== null) return
  syncingId.value = acc.id
  try {
    const { data, error } = await api.POST('/mail-accounts/{id}/sync', {
      params: { path: { id: acc.id } },
    })
    if (error || !data) {
      syncingId.value = null
      return
    }
    // 202 已受理，开始轮询
    taskAccountId.value = acc.id
    taskKind.value = 'sync'
    syncingId.value = null
    await startPolling(data.taskId, async (task) => {
      if (task.status === 'succeeded') {
        ui.pushToast('success', `${acc.emailAddress} 同步完成`)
      } else {
        ui.pushToast('error', `${acc.emailAddress} 同步失败`)
      }
      taskAccountId.value = null
      taskKind.value = null
      await fetchMailAccounts(true)
    })
  } catch (e) {
    if (isProblem(e)) {
      ui.pushToast(
        'error',
        onCode(
          e,
          {
            SYNC_ALREADY_RUNNING: () => '已有同步任务在运行',
            SECRET_REQUIRED: () => 'IMAP 未配置或未启用',
            MAIL_ACCOUNT_NOT_FOUND: () => '账号不存在',
          },
          () => '同步失败',
        ) ?? '同步失败',
      )
    } else {
      ui.pushToast('error', '同步失败，请稍后再试')
    }
    syncingId.value = null
  }
}

// ── 停用 ──

/** 停用账号 = 两开关全关（PATCH）。邮件照常可读，只是不再收发信。 */
async function disableAccount(acc: MailAccount): Promise<void> {
  try {
    const { data, error } = await api.PATCH('/mail-accounts/{id}', {
      params: { path: { id: acc.id } },
      body: { imapEnabled: false, smtpEnabled: false },
    })
    if (error || !data) return
    await fetchMailAccounts(true)
    ui.pushToast('success', `${acc.emailAddress} 已停用，邮件仍可正常查看`)
  } catch (e) {
    if (isProblem(e)) {
      ui.pushToast(
        'error',
        onCode(e, { MAIL_ACCOUNT_NOT_FOUND: () => '账号不存在' }, () => '停用失败') ?? '停用失败',
      )
    }
  }
}

// ── 删除 ──

function openDeleteConfirm(acc: MailAccount): void {
  deleteTarget.value = acc
  showDeleteConfirm.value = true
}

/**
 * 删除账号。DELETE → 202 + { taskId, messageCount } → 轮询。
 * 物理销毁全部邮件与凭据，只对已停用账号执行（后端 409 MAIL_ACCOUNT_NOT_DISABLED 兜底）。
 */
async function doDelete(): Promise<void> {
  const target = deleteTarget.value
  if (!target || deletingId.value !== null) return
  deletingId.value = target.id
  try {
    const { data, error } = await api.DELETE('/mail-accounts/{id}', {
      params: { path: { id: target.id } },
    })
    if (error || !data) {
      deletingId.value = null
      return
    }
    const messageCount = data.messageCount
    const email = target.emailAddress
    // 202 已受理，开始轮询
    taskAccountId.value = target.id
    taskKind.value = 'delete'
    deletingId.value = null
    showDeleteConfirm.value = false
    await startPolling(data.taskId, async (task) => {
      if (task.status === 'succeeded') {
        ui.pushToast('success', `${email} 已删除（${messageCount} 封邮件已物理销毁）`)
      } else {
        ui.pushToast('error', `${email} 删除失败`)
      }
      taskAccountId.value = null
      taskKind.value = null
      deleteTarget.value = null
      await fetchMailAccounts(true)
    })
  } catch (e) {
    if (isProblem(e)) {
      ui.pushToast(
        'error',
        onCode(
          e,
          {
            MAIL_ACCOUNT_NOT_DISABLED: () => '请先停用账号（关闭全部通道）再删除',
            MAINTENANCE_TASK_RUNNING: () => '有维护任务正在运行，请稍后再试',
            MAIL_ACCOUNT_NOT_FOUND: () => '账号不存在',
          },
          () => '删除失败',
        ) ?? '删除失败',
      )
    } else {
      ui.pushToast('error', '删除失败，请稍后再试')
    }
    deletingId.value = null
  }
}

// ── 初始化 ──

onMounted(async () => {
  try {
    await fetchMailAccounts(true)
  } catch {
    // 401 由 unauthMiddleware 处理；其余异常保守置空
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="h-full overflow-y-auto">
    <!-- 页头 -->
    <div class="px-6 py-4 border-b border-border bg-warm-white">
      <div class="flex items-center justify-between gap-4">
        <div>
          <h2 class="font-serif font-semibold text-base text-deep-charcoal">邮箱账号</h2>
          <p class="text-xs text-ink-400 mt-0.5">
            收信与发信通道配置。
          </p>
        </div>
        <button
          type="button"
          class="flex items-center gap-1.5 px-3 py-2 rounded-xl text-xs font-medium bg-dark-stone text-warm-white hover:bg-deep-charcoal transition"
          @click="openCreateModal"
        >
          <i class="fa-solid fa-plus text-[10px]" /> 新建账号
        </button>
      </div>
    </div>

    <!-- 加载中 -->
    <div v-if="loading" class="flex items-center justify-center py-16">
      <Spin size="text-xl" />
    </div>

    <!-- 空列表 -->
    <EmptyState
      v-else-if="mailAccounts.length === 0"
      icon="fa-solid fa-at"
      title="还没有邮箱账号"
      description="点击右上角「新建账号」添加收信/发信通道"
    />

    <!-- 账号卡片列表 -->
    <div v-else class="p-4 space-y-3 max-w-3xl">
      <div
        v-for="acc in mailAccounts"
        :key="acc.id"
        class="hygge-card bg-white rounded-2xl border border-border p-4"
      >
        <!-- 头部 -->
        <div class="flex items-start justify-between gap-3">
          <div class="min-w-0 flex-1">
            <div class="flex items-center gap-2 flex-wrap">
              <span class="text-sm font-medium text-deep-charcoal truncate">{{ acc.emailAddress }}</span>
              <span
                v-if="acc.imapEnabled"
                class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full text-[9px] bg-[#f0f4ef] text-sage border border-[#dfe6dc]"
              >
                <i class="fa-solid fa-inbox text-[8px]" /> 收信
              </span>
              <span
                v-if="acc.smtpEnabled"
                class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full text-[9px] bg-[#f0f4ef] text-sage border border-[#dfe6dc]"
              >
                <i class="fa-solid fa-paper-plane text-[8px]" /> 发信
              </span>
              <span
                v-if="isStopped(acc)"
                class="inline-flex items-center px-1.5 py-0.5 rounded-full text-[9px] bg-light-beige text-ink-400 border border-border"
              >
                已停用
              </span>
            </div>
            <p class="text-xs text-ink-400 mt-1">
              {{ acc.displayName }}
              <span class="ml-2 text-ink-300">·</span>
              <span class="ml-1">{{ formatCount(acc.messageCount) }} 封</span>
              <span class="ml-2 text-ink-300">·</span>
              <span class="ml-1">{{ formatDateTime(acc.createdAt) }}</span>
            </p>
          </div>
          <button
            type="button"
            class="flex items-center gap-1 px-2.5 py-1.5 rounded-xl text-xs text-stone-grey hover:bg-light-beige transition shrink-0"
            @click="openEditModal(acc)"
          >
            <i class="fa-solid fa-pen text-[10px]" /> 编辑
          </button>
        </div>

        <!-- 配置详情 -->
        <div class="mt-3 grid grid-cols-2 gap-x-4 gap-y-1 text-xs">
          <div class="flex items-center gap-2">
            <span class="text-ink-400 w-12 shrink-0">IMAP</span>
            <span class="text-dark-stone truncate">
              {{ acc.imapHost ? `${acc.imapHost}:${acc.imapPort ?? '?'}` : '未配置' }}
            </span>
          </div>
          <div class="flex items-center gap-2">
            <span class="text-ink-400 w-12 shrink-0">SMTP</span>
            <span class="text-dark-stone truncate">
              {{ acc.smtpHost ? `${acc.smtpHost}:${acc.smtpPort ?? '?'}` : '未配置' }}
            </span>
          </div>
        </div>

        <!-- 凭据状态（只显布尔，不回显） -->
        <div class="mt-2 flex items-center gap-4 text-xs">
          <span class="text-ink-400">凭据</span>
          <span :class="acc.secrets.imapPassword ? 'text-sage' : 'text-ink-300'" class="flex items-center gap-1">
            <i :class="acc.secrets.imapPassword ? 'fa-solid fa-check' : 'fa-solid fa-minus'" class="text-[9px]" />
            IMAP
          </span>
          <span :class="acc.secrets.smtpPassword ? 'text-sage' : 'text-ink-300'" class="flex items-center gap-1">
            <i :class="acc.secrets.smtpPassword ? 'fa-solid fa-check' : 'fa-solid fa-minus'" class="text-[9px]" />
            SMTP
          </span>
        </div>

        <!-- 操作按钮 -->
        <div class="mt-3 flex items-center gap-2 flex-wrap">
          <button
            type="button"
            class="flex items-center gap-1 px-2.5 py-1.5 rounded-xl text-xs text-stone-grey hover:bg-light-beige transition disabled:opacity-40 disabled:cursor-not-allowed"
            :disabled="!acc.secrets.imapPassword || anyTesting || taskAccountId !== null"
            @click="testConnection(acc, 'imap')"
          >
            <Spin v-if="testing === `${acc.id}-imap`" size="text-[10px]" />
            <i v-else class="fa-solid fa-plug text-[10px]" />
            测试 IMAP
          </button>
          <button
            type="button"
            class="flex items-center gap-1 px-2.5 py-1.5 rounded-xl text-xs text-stone-grey hover:bg-light-beige transition disabled:opacity-40 disabled:cursor-not-allowed"
            :disabled="!acc.secrets.smtpPassword || anyTesting || taskAccountId !== null"
            @click="testConnection(acc, 'smtp')"
          >
            <Spin v-if="testing === `${acc.id}-smtp`" size="text-[10px]" />
            <i v-else class="fa-solid fa-plug text-[10px]" />
            测试 SMTP
          </button>
          <button
            type="button"
            class="flex items-center gap-1 px-2.5 py-1.5 rounded-xl text-xs text-stone-grey hover:bg-light-beige transition disabled:opacity-40 disabled:cursor-not-allowed"
            :disabled="!acc.imapEnabled || taskAccountId !== null"
            @click="syncAccount(acc)"
          >
            <Spin v-if="syncingId === acc.id" size="text-[10px]" />
            <i v-else class="fa-solid fa-rotate text-[10px]" />
            同步
          </button>
          <button
            v-if="!isStopped(acc)"
            type="button"
            class="flex items-center gap-1 px-2.5 py-1.5 rounded-xl text-xs text-clay hover:bg-warn-bg/50 transition"
            @click="disableAccount(acc)"
          >
            <i class="fa-solid fa-pause text-[10px]" /> 停用
          </button>
          <button
            type="button"
            class="flex items-center gap-1 px-2.5 py-1.5 rounded-xl text-xs text-danger-text hover:bg-danger-bg/50 transition disabled:opacity-40 disabled:cursor-not-allowed"
            :disabled="!isStopped(acc) || taskAccountId !== null"
            :title="!isStopped(acc) ? '请先停用账号（关闭全部通道）' : ''"
            @click="openDeleteConfirm(acc)"
          >
            <i class="fa-solid fa-trash text-[10px]" /> 删除
          </button>
        </div>

        <!-- 同步/删除任务进度 -->
        <div
          v-if="taskAccountId === acc.id && activeTask"
          class="mt-2 px-3 py-2 bg-light-beige rounded-xl text-xs flex items-center gap-2"
        >
          <Spin size="text-[10px]" />
          <span class="text-stone-grey">{{ activeTask.progress || '处理中…' }}</span>
        </div>
      </div>
    </div>

    <!-- 新建/编辑表单 Modal -->
    <Modal
      v-model="showFormModal"
      :title="isEditMode ? '编辑邮箱账号' : '新建邮箱账号'"
    >
      <form class="space-y-5" @submit.prevent="saveForm">
        <!-- 基本信息 -->
        <div class="space-y-3">
          <div class="space-y-1.5">
            <label class="block text-[10px] font-mono uppercase tracking-wider text-ink-400">
              邮箱地址 *
            </label>
            <input
              v-model="fEmailAddress"
              type="email"
              class="w-full hygge-input rounded-xl px-3 py-2 text-sm"
              placeholder="user@example.com"
              autocomplete="off"
            />
            <p v-if="formErrors.emailAddress" class="text-[10px] text-danger-text">{{ formErrors.emailAddress }}</p>
          </div>
          <div class="space-y-1.5">
            <label class="block text-[10px] font-mono uppercase tracking-wider text-ink-400">
              显示名（兼发信署名）*
            </label>
            <input
              v-model="fDisplayName"
              type="text"
              class="w-full hygge-input rounded-xl px-3 py-2 text-sm"
              placeholder="张三"
              autocomplete="off"
            />
            <p v-if="formErrors.displayName" class="text-[10px] text-danger-text">{{ formErrors.displayName }}</p>
          </div>
        </div>

        <!-- IMAP 收信 -->
        <div class="border-t border-border-soft pt-4 space-y-3">
          <h4 class="text-xs font-medium text-deep-charcoal flex items-center gap-1.5">
            <i class="fa-solid fa-inbox text-[10px] text-ink-400" /> IMAP 收信
          </h4>
          <!-- 密码排在开关前：启用前必须先配好凭据 -->
          <div class="space-y-1.5">
            <label class="block text-[10px] font-mono uppercase tracking-wider text-ink-400">
              IMAP 密码
            </label>
            <div class="flex items-center gap-2">
              <input
                v-model="fImapPassword"
                type="password"
                class="flex-1 hygge-input rounded-xl px-3 py-2 text-sm"
                :placeholder="isEditMode && editingAccount?.secrets.imapPassword ? '已配置（输入新值覆盖）' : '输入 IMAP 密码'"
                autocomplete="new-password"
              />
              <span
                v-if="isEditMode && editingAccount?.secrets.imapPassword"
                class="text-[10px] text-sage flex items-center gap-1 shrink-0"
              >
                <i class="fa-solid fa-check" /> 已配置
              </span>
            </div>
          </div>
          <div class="grid grid-cols-2 gap-3">
            <div class="space-y-1.5">
              <label class="block text-[10px] font-mono uppercase tracking-wider text-ink-400">服务器</label>
              <input
                v-model="fImapHost"
                type="text"
                class="w-full hygge-input rounded-xl px-3 py-2 text-sm"
                placeholder="imap.example.com"
                autocomplete="off"
              />
            </div>
            <div class="space-y-1.5">
              <label class="block text-[10px] font-mono uppercase tracking-wider text-ink-400">端口</label>
              <input
                v-model="fImapPort"
                type="number"
                class="w-full hygge-input rounded-xl px-3 py-2 text-sm"
                placeholder="993"
              />
            </div>
          </div>
          <div class="space-y-1.5">
            <label class="block text-[10px] font-mono uppercase tracking-wider text-ink-400">用户名</label>
            <input
              v-model="fImapUsername"
              type="text"
              class="w-full hygge-input rounded-xl px-3 py-2 text-sm"
              placeholder="user@example.com（与邮箱地址相同时可留空）"
              autocomplete="off"
            />
          </div>
          <div class="space-y-1.5">
            <label class="block text-[10px] font-mono uppercase tracking-wider text-ink-400">
              Junk 文件夹 fallback
            </label>
            <input
              v-model="fImapFolders"
              type="text"
              class="w-full hygge-input rounded-xl px-3 py-2 text-sm"
              placeholder="逗号分隔，如 Junk,Spam（服务器声明 SPECIAL-USE 时可留空）"
              autocomplete="off"
            />
            <p class="text-[10px] text-ink-400">服务器未自动识别垃圾邮件文件夹时作为 fallback；INBOX 无需配置。</p>
          </div>
          <div v-if="isEditMode" class="flex items-center gap-2">
            <button
              type="button"
              class="relative inline-flex h-5 w-9 items-center rounded-full transition"
              :class="fImapEnabled ? 'bg-sage' : 'bg-border-strong'"
              @click="fImapEnabled = !fImapEnabled"
            >
              <span
                class="inline-block h-3.5 w-3.5 transform rounded-full bg-white transition"
                :class="fImapEnabled ? 'translate-x-5' : 'translate-x-1'"
              />
            </button>
            <span class="text-xs text-stone-grey">启用收信</span>
          </div>
        </div>

        <!-- SMTP 发信 -->
        <div class="border-t border-border-soft pt-4 space-y-3">
          <h4 class="text-xs font-medium text-deep-charcoal flex items-center gap-1.5">
            <i class="fa-solid fa-paper-plane text-[10px] text-ink-400" /> SMTP 发信
          </h4>
          <div class="space-y-1.5">
            <label class="block text-[10px] font-mono uppercase tracking-wider text-ink-400">
              SMTP 密码
            </label>
            <div class="flex items-center gap-2">
              <input
                v-model="fSmtpPassword"
                type="password"
                class="flex-1 hygge-input rounded-xl px-3 py-2 text-sm"
                :placeholder="isEditMode && editingAccount?.secrets.smtpPassword ? '已配置（输入新值覆盖）' : '输入 SMTP 密码'"
                autocomplete="new-password"
              />
              <span
                v-if="isEditMode && editingAccount?.secrets.smtpPassword"
                class="text-[10px] text-sage flex items-center gap-1 shrink-0"
              >
                <i class="fa-solid fa-check" /> 已配置
              </span>
            </div>
          </div>
          <div class="grid grid-cols-2 gap-3">
            <div class="space-y-1.5">
              <label class="block text-[10px] font-mono uppercase tracking-wider text-ink-400">服务器</label>
              <input
                v-model="fSmtpHost"
                type="text"
                class="w-full hygge-input rounded-xl px-3 py-2 text-sm"
                placeholder="smtp.example.com"
                autocomplete="off"
              />
            </div>
            <div class="space-y-1.5">
              <label class="block text-[10px] font-mono uppercase tracking-wider text-ink-400">端口</label>
              <input
                v-model="fSmtpPort"
                type="number"
                class="w-full hygge-input rounded-xl px-3 py-2 text-sm"
                placeholder="587"
              />
            </div>
          </div>
          <div class="space-y-1.5">
            <label class="block text-[10px] font-mono uppercase tracking-wider text-ink-400">用户名</label>
            <input
              v-model="fSmtpUsername"
              type="text"
              class="w-full hygge-input rounded-xl px-3 py-2 text-sm"
              placeholder="user@example.com（与邮箱地址相同时可留空）"
              autocomplete="off"
            />
          </div>
          <div v-if="isEditMode" class="flex items-center gap-2">
            <button
              type="button"
              class="relative inline-flex h-5 w-9 items-center rounded-full transition"
              :class="fSmtpEnabled ? 'bg-sage' : 'bg-border-strong'"
              @click="fSmtpEnabled = !fSmtpEnabled"
            >
              <span
                class="inline-block h-3.5 w-3.5 transform rounded-full bg-white transition"
                :class="fSmtpEnabled ? 'translate-x-5' : 'translate-x-1'"
              />
            </button>
            <span class="text-xs text-stone-grey">启用发信</span>
          </div>
        </div>
      </form>

      <template #footer>
        <button
          type="button"
          class="px-4 py-2 rounded-xl text-xs font-medium text-stone-grey hover:bg-light-beige transition"
          @click="showFormModal = false"
        >
          取消
        </button>
        <button
          type="button"
          class="flex items-center gap-1.5 px-4 py-2 rounded-xl text-xs font-medium bg-dark-stone text-warm-white hover:bg-deep-charcoal transition disabled:opacity-50 disabled:cursor-not-allowed"
          :disabled="saving"
          @click="saveForm"
        >
          <Spin v-if="saving" size="text-xs" />
          <span>{{ saving ? '保存中…' : '保存' }}</span>
        </button>
      </template>
    </Modal>

    <!-- 删除确认（手输邮箱核对 + 显示 messageCount） -->
    <ConfirmDialog
      v-model="showDeleteConfirm"
      title="删除邮箱账号"
      :message="deleteTarget
        ? `将永久删除 ${deleteTarget.messageCount} 封邮件与全部凭据，不可恢复。删除后该邮箱地址可重新添加。`
        : '将永久删除该账号的全部邮件与凭据，不可恢复。'"
      confirm-text="删除"
      danger
      require-text-input
      :require-text-match="deleteTarget?.emailAddress ?? ''"
      @confirm="doDelete"
    />
  </div>
</template>
