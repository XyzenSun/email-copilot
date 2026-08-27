<script setup lang="ts">
/**
 * AI 连接设置。FRONTEND.md §3.7。
 *
 * 全部可热改、立即生效、不需要重启（后端持有可替换的 ChatModel 引用）。
 *
 * 安全硬约束：
 *   - 凭据永不回显：ai-key / mcp-key / tavily-key 写入后只显布尔，输入框 type=password 提交后清空。
 *   - 测试连接 200+status 不进 error 分支：连不上是 200+failed/timeout，读 message 显示。
 *   - aiModel 自由文本不做下拉——服务商随时加新型号，写死枚举等于每次都要改代码。
 *   - aiContextWindowK 紧挨 aiModel，旁注「系统不会根据型号名自动填，换型号顺带核对」。
 *   - 主密钥状态只读，不可在 UI 修改（来自环境变量）。
 */
import { ref, computed, onMounted } from 'vue'
import { api } from '@/api/client'
import { isProblem, onCode, fieldErrors } from '@/api/errors'
import { useUiStore } from '@/stores/ui'
import type { components } from '@/api/types.gen'
import Spin from '@/components/ui/Spin.vue'
import Banner from '@/components/ui/Banner.vue'

type SystemSettings = components['schemas']['SystemSettings']
type SystemSettingsUpdateRequest = components['schemas']['SystemSettingsUpdateRequest']
type AiTestResult = components['schemas']['AiTestResult']

const ui = useUiStore()

// ── 配置状态 ──
const settings = ref<SystemSettings | null>(null)
const loading = ref(true)

// ── 表单字段（number 用 string ref + 手动 parse，避免 v-model.number 空值类型问题） ──
const formProvider = ref<'openai' | 'anthropic'>('openai')
const formBaseUrl = ref('')
const formModel = ref('')
const formContextWindowK = ref('200')
const formTimeoutSeconds = ref('30')
const formErrors = ref<Record<string, string>>({})
const saving = ref(false)

// ── API Key 写入 ──
const aiKeyInput = ref('')
const mcpKeyInput = ref('')
const tavilyKeyInput = ref('')
const savingAiKey = ref(false)
const savingMcpKey = ref(false)
const savingTavilyKey = ref(false)

// ── 测试连接 ──
const testing = ref(false)
const testResult = ref<AiTestResult | null>(null)

const aiReady = computed(() => settings.value?.aiReady ?? false)

/** 初始化表单字段 */
function populateForm(s: SystemSettings): void {
  formProvider.value = s.aiProvider
  formBaseUrl.value = s.aiBaseUrl ?? ''
  formModel.value = s.aiModel ?? ''
  formContextWindowK.value = String(s.aiContextWindowK)
  formTimeoutSeconds.value = String(s.aiTimeoutSeconds)
  formErrors.value = {}
}

/** 加载配置 */
async function loadSettings(): Promise<void> {
  loading.value = true
  try {
    const { data, error } = await api.GET('/settings/system')
    if (error || !data) return
    settings.value = data
    populateForm(data)
  } finally {
    loading.value = false
  }
}

/**
 * 保存连接配置（PATCH，热改立即生效）。
 * 全部字段可选，只传要改的——但这里全传，简单且幂等。
 */
async function saveConfig(): Promise<void> {
  if (saving.value) return
  const ctxK = Number(formContextWindowK.value)
  const timeout = Number(formTimeoutSeconds.value)
  if (!ctxK || ctxK <= 0) {
    ui.pushToast('error', '上下文窗口必须为正数')
    return
  }
  if (!timeout || timeout <= 0) {
    ui.pushToast('error', '超时必须为正数')
    return
  }

  saving.value = true
  formErrors.value = {}
  try {
    const body: SystemSettingsUpdateRequest = {
      aiProvider: formProvider.value,
      aiBaseUrl: formBaseUrl.value.trim() || null, // 空=用官方地址
      aiModel: formModel.value.trim() || null, // 空=未配置（首次部署正常状态）
      aiContextWindowK: ctxK,
      aiTimeoutSeconds: timeout,
    }
    const { data, error } = await api.PATCH('/settings/system', { body })
    if (error || !data) return
    // PATCH 响应覆盖本地（含 aiReady、aiApiKeyConfigured 等）
    settings.value = data
    populateForm(data)
    ui.pushToast('success', 'AI 配置已保存，立即生效')
  } catch (e) {
    if (isProblem(e)) {
      formErrors.value = fieldErrors(e)
      ui.pushToast(
        'error',
        onCode(
          e,
          {
            VALIDATION_FAILED: () => '部分字段有误，请检查标红项',
            AI_SETTINGS_INVALID: () => '端点不是合法 URL 或超时越界',
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

/**
 * 写入 AI API Key（PUT /ai-key → 204 永不回显）。
 * 写入后清空输入框，刷新 settings 取 aiApiKeyConfigured 布尔。
 */
async function saveAiKey(): Promise<void> {
  if (savingAiKey.value || !aiKeyInput.value) return
  savingAiKey.value = true
  try {
    const { error } = await api.PUT('/settings/system/ai-key', {
      body: { value: aiKeyInput.value },
    })
    if (error) return
    aiKeyInput.value = '' // 提交后清空——凭据永不回显
    await loadSettings() // 刷新 aiApiKeyConfigured / aiReady 布尔
    ui.pushToast('success', 'AI API Key 已写入，立即生效')
  } catch (e) {
    if (isProblem(e)) {
      ui.pushToast(
        'error',
        onCode(e, { VALIDATION_FAILED: () => 'API Key 格式有误' }, () => '写入失败') ?? '写入失败',
      )
    } else {
      ui.pushToast('error', '写入失败，请稍后再试')
    }
  } finally {
    savingAiKey.value = false
  }
}

/**
 * 写入 Exa MCP Key（PUT /mcp-key → 204 永不回显）。
 * 同样提交后清空，刷新 mcpApiKeyConfigured 布尔。
 */
async function saveMcpKey(): Promise<void> {
  if (savingMcpKey.value || !mcpKeyInput.value) return
  savingMcpKey.value = true
  try {
    const { error } = await api.PUT('/settings/system/mcp-key', {
      body: { value: mcpKeyInput.value },
    })
    if (error) return
    mcpKeyInput.value = ''
    await loadSettings()
    ui.pushToast('success', 'MCP Key 已写入，立即生效')
  } catch (e) {
    if (isProblem(e)) {
      ui.pushToast(
        'error',
        onCode(e, { VALIDATION_FAILED: () => 'MCP Key 格式有误' }, () => '写入失败') ?? '写入失败',
      )
    } else {
      ui.pushToast('error', '写入失败，请稍后再试')
    }
  } finally {
    savingMcpKey.value = false
  }
}

/**
 * 写入 Tavily Key（PUT /tavily-key → 204 永不回显）。
 * 同样提交后清空，刷新 tavilyApiKeyConfigured 布尔。
 * 本阶段只存 key 暂不接入对话工具链。
 */
async function saveTavilyKey(): Promise<void> {
  if (savingTavilyKey.value || !tavilyKeyInput.value) return
  savingTavilyKey.value = true
  try {
    const { error } = await api.PUT('/settings/system/tavily-key', {
      body: { value: tavilyKeyInput.value },
    })
    if (error) return
    tavilyKeyInput.value = ''
    await loadSettings()
    ui.pushToast('success', 'Tavily Key 已写入，立即生效')
  } catch (e) {
    if (isProblem(e)) {
      ui.pushToast(
        'error',
        onCode(e, { VALIDATION_FAILED: () => 'Tavily Key 格式有误' }, () => '写入失败') ?? '写入失败',
      )
    } else {
      ui.pushToast('error', '写入失败，请稍后再试')
    }
  } finally {
    savingTavilyKey.value = false
  }
}

/**
 * 测试 AI 连接（POST /test → 200 + AiTestResult）。
 * **failed/timeout 也是 200**——请求被正确处理，业务结果是失败。
 * 读 status + message 显示，不进 error 分支、不重试。
 * 409 AI_NOT_CONFIGURED（型号名或 key 缺失）走 catch。
 */
async function testAi(): Promise<void> {
  if (testing.value) return
  testing.value = true
  testResult.value = null
  try {
    const { data, error } = await api.POST('/settings/system/test')
    if (error || !data) return
    testResult.value = data
    // 200+status：成功显示延迟，失败显示诊断——都是业务结果
    if (data.status === 'succeeded') {
      ui.pushToast('success', `AI 连接成功（${data.latencyMs}ms）`)
    } else {
      const label = data.status === 'timeout' ? '超时' : '失败'
      ui.pushToast('error', `AI 连接${label}：${data.message}`)
    }
  } catch (e) {
    if (isProblem(e)) {
      ui.pushToast(
        'error',
        onCode(
          e,
          { AI_NOT_CONFIGURED: () => 'AI 尚未配置（型号名或 key 缺失）' },
          () => '测试失败',
        ) ?? '测试失败',
      )
    } else {
      ui.pushToast('error', '测试失败，请稍后再试')
    }
  } finally {
    testing.value = false
  }
}

onMounted(loadSettings)
</script>

<template>
  <div class="h-full overflow-y-auto">
    <!-- 页头 -->
    <div class="px-6 py-4 border-b border-border bg-warm-white">
      <h2 class="font-serif font-semibold text-base text-deep-charcoal">AI 连接</h2>
      <p class="text-xs text-ink-400 mt-0.5">
        全部可热改、立即生效、无需重启。
      </p>
    </div>

    <!-- 加载中 -->
    <div v-if="loading" class="flex items-center justify-center py-16">
      <Spin size="text-xl" />
    </div>

    <div v-else-if="settings" class="p-6 max-w-2xl space-y-5">
      <!-- AI 未配置提示 -->
      <Banner v-if="!aiReady" variant="warn">
        AI 尚未配置。收信正常，但不分类、不打标签、不能对话。请配置型号名和 API Key。
      </Banner>

      <!-- 连接配置 -->
      <div class="hygge-card bg-white rounded-2xl border border-border p-5 space-y-4">
        <h3 class="text-[10px] font-mono uppercase tracking-wider text-ink-400 font-semibold">连接配置</h3>

        <!-- Provider -->
        <div class="space-y-1.5">
          <label class="block text-xs font-medium text-stone-grey">Provider</label>
          <select
            v-model="formProvider"
            class="w-full hygge-input rounded-xl px-3 py-2 text-sm"
          >
            <option value="openai">openai</option>
            <option value="anthropic">anthropic</option>
          </select>
          <p class="text-[10px] text-ink-400">第三方服务通常选 openai 并填自定义端点。</p>
        </div>

        <!-- 自定义端点 -->
        <div class="space-y-1.5">
          <label class="block text-xs font-medium text-stone-grey">自定义端点</label>
          <input
            v-model="formBaseUrl"
            type="url"
            class="w-full hygge-input rounded-xl px-3 py-2 text-sm"
            placeholder="https://api.example.com/v1（留空用官方地址）"
            autocomplete="off"
          />
          <p class="text-[10px] text-ink-400">留空表示用该 provider 的官方地址。</p>
          <p v-if="formErrors.aiBaseUrl" class="text-[10px] text-danger-text">{{ formErrors.aiBaseUrl }}</p>
        </div>

        <!-- 型号名（自由文本，不做下拉） -->
        <div class="space-y-1.5">
          <label class="block text-xs font-medium text-stone-grey">型号名</label>
          <input
            v-model="formModel"
            type="text"
            class="w-full hygge-input rounded-xl px-3 py-2 text-sm"
            placeholder="gpt-4o / claude-3.5-sonnet / 自定义"
            autocomplete="off"
          />
          <p class="text-[10px] text-ink-400">按服务商文档填写型号名。</p>
          <p v-if="formErrors.aiModel" class="text-[10px] text-danger-text">{{ formErrors.aiModel }}</p>
        </div>

        <!-- 上下文窗口（紧挨型号名，改型号顺带核对） -->
        <div class="space-y-1.5">
          <label class="block text-xs font-medium text-stone-grey">上下文窗口（k）</label>
          <input
            v-model="formContextWindowK"
            type="number"
            class="w-full hygge-input rounded-xl px-3 py-2 text-sm"
            placeholder="200"
          />
          <p class="text-[10px] text-ink-400">
            单位 k（200 = 200000 token）。换型号请顺带核对。
          </p>
          <p v-if="formErrors.aiContextWindowK" class="text-[10px] text-danger-text">{{ formErrors.aiContextWindowK }}</p>
        </div>

        <!-- 超时 -->
        <div class="space-y-1.5">
          <label class="block text-xs font-medium text-stone-grey">单次调用超时（秒）</label>
          <input
            v-model="formTimeoutSeconds"
            type="number"
            class="w-full hygge-input rounded-xl px-3 py-2 text-sm"
            placeholder="30"
          />
          <p v-if="formErrors.aiTimeoutSeconds" class="text-[10px] text-danger-text">{{ formErrors.aiTimeoutSeconds }}</p>
        </div>

        <!-- AI API Key（并入连接配置框，凭据永不回显） -->
        <div class="space-y-1.5">
          <div class="flex items-center justify-between">
            <label class="block text-xs font-medium text-stone-grey">AI API Key</label>
            <span
              :class="settings.aiApiKeyConfigured ? 'text-sage' : 'text-ink-300'"
              class="flex items-center gap-1 text-[10px]"
            >
              <i :class="settings.aiApiKeyConfigured ? 'fa-solid fa-check' : 'fa-solid fa-minus'" />
              {{ settings.aiApiKeyConfigured ? '已配置' : '未配置' }}
            </span>
          </div>
          <div class="flex items-center gap-2">
            <input
              v-model="aiKeyInput"
              type="password"
              class="flex-1 hygge-input rounded-xl px-3 py-2 text-sm"
              :placeholder="settings.aiApiKeyConfigured ? '输入新值覆盖（当前已配置）' : '输入 API Key'"
              autocomplete="new-password"
              @keyup.enter="saveAiKey"
            />
            <button
              type="button"
              class="flex items-center gap-1 px-3 py-2 rounded-xl text-xs font-medium bg-light-beige text-dark-stone border border-border hover:bg-sand/50 transition disabled:opacity-40 disabled:cursor-not-allowed shrink-0"
              :disabled="savingAiKey || !aiKeyInput"
              @click="saveAiKey"
            >
              <Spin v-if="savingAiKey" size="text-xs" />
              写入
            </button>
          </div>
        </div>

        <button
          type="button"
          class="flex items-center gap-1.5 px-4 py-2 rounded-xl text-xs font-medium bg-dark-stone text-warm-white hover:bg-deep-charcoal transition disabled:opacity-50 disabled:cursor-not-allowed"
          :disabled="saving"
          @click="saveConfig"
        >
          <Spin v-if="saving" size="text-xs" />
          <span>{{ saving ? '保存中…' : '保存配置' }}</span>
        </button>
      </div>

      <!-- MCP 工具配置 -->
      <div class="hygge-card bg-white rounded-2xl border border-border p-5 space-y-3">
        <h3 class="text-[10px] font-mono uppercase tracking-wider text-ink-400 font-semibold">MCP 工具配置</h3>
        <p class="text-[10px] text-ink-400">
          对话 AI 的网络搜索工具用。
        </p>

        <!-- Exa MCP Key -->
        <div class="space-y-1.5">
          <div class="flex items-center justify-between">
            <label class="block text-xs font-medium text-stone-grey">Exa API Key</label>
            <span
              :class="settings.mcpApiKeyConfigured ? 'text-sage' : 'text-ink-300'"
              class="flex items-center gap-1 text-[10px]"
            >
              <i :class="settings.mcpApiKeyConfigured ? 'fa-solid fa-check' : 'fa-solid fa-minus'" />
              {{ settings.mcpApiKeyConfigured ? '已配置' : '未配置' }}
            </span>
          </div>
          <div class="flex items-center gap-2">
            <input
              v-model="mcpKeyInput"
              type="password"
              class="flex-1 hygge-input rounded-xl px-3 py-2 text-sm"
              :placeholder="settings.mcpApiKeyConfigured ? '输入新值覆盖（当前已配置）' : '输入 Exa API Key'"
              autocomplete="new-password"
              @keyup.enter="saveMcpKey"
            />
            <button
              type="button"
              class="flex items-center gap-1 px-3 py-2 rounded-xl text-xs font-medium bg-light-beige text-dark-stone border border-border hover:bg-sand/50 transition disabled:opacity-40 disabled:cursor-not-allowed shrink-0"
              :disabled="savingMcpKey || !mcpKeyInput"
              @click="saveMcpKey"
            >
              <Spin v-if="savingMcpKey" size="text-xs" />
              写入
            </button>
          </div>
        </div>
        <!-- Tavily API Key（凭据永不回显，本阶段只存 key 暂不接入对话工具链） -->
        <div class="space-y-1.5">
          <div class="flex items-center justify-between">
            <label class="block text-xs font-medium text-stone-grey">Tavily API Key</label>
            <span
              :class="settings.tavilyApiKeyConfigured ? 'text-sage' : 'text-ink-300'"
              class="flex items-center gap-1 text-[10px]"
            >
              <i :class="settings.tavilyApiKeyConfigured ? 'fa-solid fa-check' : 'fa-solid fa-minus'" />
              {{ settings.tavilyApiKeyConfigured ? '已配置' : '未配置' }}
            </span>
          </div>
          <div class="flex items-center gap-2">
            <input
              v-model="tavilyKeyInput"
              type="password"
              class="flex-1 hygge-input rounded-xl px-3 py-2 text-sm"
              :placeholder="settings.tavilyApiKeyConfigured ? '输入新值覆盖（当前已配置）' : '输入 Tavily API Key'"
              autocomplete="new-password"
              @keyup.enter="saveTavilyKey"
            />
            <button
              type="button"
              class="flex items-center gap-1 px-3 py-2 rounded-xl text-xs font-medium bg-light-beige text-dark-stone border border-border hover:bg-sand/50 transition disabled:opacity-40 disabled:cursor-not-allowed shrink-0"
              :disabled="savingTavilyKey || !tavilyKeyInput"
              @click="saveTavilyKey"
            >
              <Spin v-if="savingTavilyKey" size="text-xs" />
              写入
            </button>
          </div>
        </div>
      </div>

      <!-- 测试连接 -->
      <div class="hygge-card bg-white rounded-2xl border border-border p-5 space-y-3">
        <h3 class="text-[10px] font-mono uppercase tracking-wider text-ink-400 font-semibold">测试连接</h3>
        <p class="text-[10px] text-ink-400">
          点这里验证 AI 连通性。
        </p>
        <!-- 测试结果（200+status：succeeded/failed/timeout 都不是错误分支） -->
        <div
          v-if="testResult"
          class="px-3 py-2 rounded-xl text-xs"
          :class="testResult.status === 'succeeded'
            ? 'bg-[#f0f4ef] text-sage border border-[#dfe6dc]'
            : 'bg-danger-bg text-danger-text border border-danger-border'"
        >
          <span v-if="testResult.status === 'succeeded'">
            <i class="fa-solid fa-check mr-1" />连接成功（{{ testResult.latencyMs }}ms）
          </span>
          <span v-else>
            <i class="fa-solid fa-circle-exclamation mr-1" />连接{{ testResult.status === 'timeout' ? '超时' : '失败' }}：{{ testResult.message }}
          </span>
        </div>
        <button
          type="button"
          class="flex items-center gap-1.5 px-4 py-2 rounded-xl text-xs font-medium bg-light-beige text-dark-stone border border-border hover:bg-sand/50 transition disabled:opacity-40 disabled:cursor-not-allowed"
          :disabled="testing || !aiReady"
          @click="testAi"
        >
          <Spin v-if="testing" size="text-xs" />
          <i v-else class="fa-solid fa-plug text-[10px]" />
          {{ testing ? '测试中…' : '测试连接' }}
        </button>
        <p v-if="!aiReady" class="text-[10px] text-clay">
          先配置型号名和 API Key 再测试。
        </p>
      </div>

      <!-- 主密钥状态（只读） -->
      <div class="hygge-card bg-white rounded-2xl border border-border p-5 space-y-2">
        <h3 class="text-[10px] font-mono uppercase tracking-wider text-ink-400 font-semibold">主密钥状态（只读）</h3>
        <div class="flex items-center gap-3 text-xs">
          <span class="text-ink-400 w-24">环境注入</span>
          <span
            :class="settings.masterKeyPresent ? 'text-sage' : 'text-clay'"
            class="flex items-center gap-1"
          >
            <i :class="settings.masterKeyPresent ? 'fa-solid fa-check' : 'fa-solid fa-triangle-exclamation'" />
            {{ settings.masterKeyPresent ? '已注入' : '未注入' }}
          </span>
        </div>
        <div class="flex items-center gap-3 text-xs">
          <span class="text-ink-400 w-24">与密文匹配</span>
          <span
            :class="settings.masterKeyMatchesCiphertext ? 'text-sage' : 'text-danger-text'"
            class="flex items-center gap-1"
          >
            <i :class="settings.masterKeyMatchesCiphertext ? 'fa-solid fa-check' : 'fa-solid fa-circle-exclamation'" />
            {{ settings.masterKeyMatchesCiphertext ? '匹配' : '不匹配' }}
          </span>
        </div>
        <p v-if="!settings.masterKeyMatchesCiphertext" class="text-[10px] text-danger-text leading-relaxed">
          主密钥与库中密文不匹配，请联系管理员检查环境变量。
        </p>
      </div>
    </div>
  </div>
</template>
