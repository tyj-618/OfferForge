<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { apiKeyApi, billingApi, billingState, refreshBillingState } from '../api'
import { notifyError } from '../utils/errors'
import { toast } from '../toast'
import { getPreferredModel, setPreferredModel } from '../utils/modelPreference'

// 千问固定配置与后端 ApiKeyProvider 规则保持一致
const QIANWEN_BASE_URL = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
const QIANWEN_MODEL = 'qwen-plus'

const status = ref(null)
const loading = ref(true)
const provider = ref('QIANWEN')
const baseUrl = ref(QIANWEN_BASE_URL)
const model = ref(QIANWEN_MODEL)
const apiKey = ref('')
const saving = ref(false)
const removing = ref(false)

const isQianwen = computed(() => provider.value === 'QIANWEN')

const providerLabel = computed(() =>
  status.value?.provider === 'QIANWEN' ? '通义千问' : 'OpenAI 兼容'
)

const canSave = computed(() => {
  if (!apiKey.value.trim()) {
    return false
  }
  // OpenAI 兼容接口必须提供 Base URL 与模型
  return isQianwen.value || (baseUrl.value.trim() && model.value.trim())
})

// 切换千问时自动填充固定值；切换 OpenAI 兼容时清空供用户输入
watch(provider, (value) => {
  if (value === 'QIANWEN') {
    baseUrl.value = QIANWEN_BASE_URL
    model.value = QIANWEN_MODEL
  } else {
    baseUrl.value = ''
    model.value = ''
  }
})

onMounted(loadStatus)

async function loadStatus() {
  loading.value = true
  try {
    status.value = await apiKeyApi.get()
  } catch (e) {
    notifyError(e, loadStatus)
  } finally {
    loading.value = false
  }
}

async function saveKey() {
  if (!canSave.value || saving.value) {
    return
  }
  saving.value = true
  try {
    status.value = await apiKeyApi.save({
      provider: provider.value,
      // 千问由后端固定 Base URL / 缺省模型，无需上送
      baseUrl: isQianwen.value ? null : baseUrl.value.trim(),
      model: isQianwen.value ? null : model.value.trim(),
      apiKey: apiKey.value.trim()
    })
    apiKey.value = ''
    toast.success('API Key 已保存')
  } catch (e) {
    notifyError(e)
  } finally {
    saving.value = false
  }
}

async function removeKey() {
  if (removing.value) {
    return
  }
  removing.value = true
  try {
    await apiKeyApi.remove()
    status.value = { configured: false, provider: null }
    toast.success('API Key 已删除，将恢复使用系统免费额度')
  } catch (e) {
    notifyError(e)
  } finally {
    removing.value = false
  }
}

// 官方模型选择：未配置自带 Key 时生效，模拟面试/专项训练将使用所选官方模型；
// 计费开关关闭时仅官方免费档可选（如 DeepSeek-V4-Flash），付费档待充值功能开放后呈现
const modelOptions = ref([])
const preferredModel = ref(getPreferredModel())
const savedModel = ref(getPreferredModel())
const savingModel = ref(false)
const modelJustSaved = ref(false)
let modelSavedTimer = null

// 下拉当前值与已保存值不同 → 有未保存变更（保存按钮仅此时可用）
const modelDirty = computed(() => preferredModel.value !== savedModel.value)
const savedModelName = computed(() => {
  if (!savedModel.value) {
    return ''
  }
  const found = modelOptions.value.find((item) => item.id === savedModel.value)
  return found ? found.name : savedModel.value
})

const selectableModels = computed(() =>
  modelOptions.value.filter((item) => billingState.enabled || !item.paidOnly)
)

async function loadModelOptions() {
  try {
    modelOptions.value = (await billingApi.models()) || []
    // 已保存的偏好若已不可选（如付费档下线），回退系统默认
    if (preferredModel.value && !selectableModels.value.some((item) => item.id === preferredModel.value)) {
      preferredModel.value = ''
    }
  } catch {
    // 价目加载失败不阻断页面：仅系统默认档可选，下次进入重试即恢复（此时偏好保留不重置）
  }
}

function saveModelPreference() {
  if (savingModel.value || !modelDirty.value) {
    return
  }
  savingModel.value = true
  try {
    setPreferredModel(preferredModel.value)
    savedModel.value = preferredModel.value
    // 卡片内联反馈短暂闪现后自动收起，常驻状态由下方「当前生效」条承载
    modelJustSaved.value = true
    clearTimeout(modelSavedTimer)
    modelSavedTimer = setTimeout(() => {
      modelJustSaved.value = false
    }, 2400)
    toast.success(preferredModel.value ? '已切换官方模型，下一场面试/训练生效' : '已恢复系统默认模型，下一场面试/训练生效')
  } finally {
    savingModel.value = false
  }
}

onUnmounted(() => {
  clearTimeout(modelSavedTimer)
})

onMounted(() => {
  refreshBillingState().then(loadModelOptions)
})
</script>

<template>
  <div class="page">
    <h1 class="page-title">设置</h1>

    <div class="card settings-card">
      <h2>API Key 配置</h2>
      <p class="muted">
        配置自己的 API Key 后，模拟面试将使用你的 Key 调用模型，不受每日免费额度限制；
        未配置时使用系统 Key，按每日免费额度计次（每场面试开始时扣减一次）。
      </p>

      <div v-if="loading" class="muted loading-text">加载中…</div>

      <!-- 已配置：仅展示 provider，绝不回显明文 Key -->
      <div v-else-if="status?.configured" class="key-status">
        <div class="key-status-row">
          <span class="badge success">已配置</span>
          <span>当前服务商：{{ providerLabel }}</span>
        </div>
        <p class="muted">模拟面试将优先使用你自己的 API Key，不消耗免费额度。</p>
        <button class="secondary" :disabled="removing" @click="removeKey">
          {{ removing ? '删除中…' : '删除 API Key' }}
        </button>
      </div>

      <!-- 未配置：Provider 下拉 + 连接信息 + Key 输入 -->
      <form v-else class="key-form" @submit.prevent="saveKey">
        <label class="field">
          <span class="field-label muted">服务商</span>
          <select v-model="provider" :disabled="saving">
            <option value="QIANWEN">通义千问（DashScope）</option>
            <option value="OPENAI_COMPATIBLE">OpenAI 兼容接口</option>
          </select>
        </label>
        <label class="field">
          <span class="field-label muted">Base URL</span>
          <input
            v-model="baseUrl"
            :readonly="isQianwen"
            :disabled="saving"
            :class="{ readonly: isQianwen }"
            placeholder="https://api.example.com/v1"
          />
        </label>
        <label class="field">
          <span class="field-label muted">模型</span>
          <input
            v-model="model"
            :readonly="isQianwen"
            :disabled="saving"
            :class="{ readonly: isQianwen }"
            placeholder="如 gpt-4o-mini"
          />
        </label>
        <label class="field">
          <span class="field-label muted">API Key</span>
          <input
            v-model="apiKey"
            type="password"
            :disabled="saving"
            placeholder="sk-..."
            autocomplete="off"
          />
        </label>
        <p v-if="isQianwen" class="muted hint">选择通义千问时 Base URL 与模型已自动填充，无需修改。</p>
        <button type="submit" :disabled="saving || !canSave">
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </form>
    </div>

    <div class="card settings-card">
      <h2>官方模型选择</h2>
      <p class="muted">
        未配置自带 API Key 时，模拟面试与专项训练将使用此处选择的官方模型（仍按每日免费额度计次）；
        已配置自带 Key 时以你的 Key 为准，此选择不生效。
      </p>
      <div class="key-form">
        <label class="field">
          <span class="field-label muted">模型</span>
          <select v-model="preferredModel" :disabled="savingModel">
            <option value="">系统默认模型</option>
            <option v-for="item in selectableModels" :key="item.id" :value="item.id">
              {{ item.name }}
            </option>
          </select>
        </label>
        <p v-if="modelDirty" class="hint warning-text">有未保存的变更，保存后从下一场面试/训练开始生效。</p>
        <p v-else class="muted hint">切换后保存，从下一场面试/训练开始生效，进行中的场次不受影响。</p>
        <div class="model-action-row">
          <button type="button" :disabled="savingModel || !modelDirty" @click="saveModelPreference">
            {{ savingModel ? '保存中…' : '保存' }}
          </button>
          <span v-if="modelJustSaved" class="saved-flash">✓ 已保存</span>
        </div>
      </div>

      <!-- 常驻生效状态：与 API Key「已配置」状态条同一呈现模式 -->
      <div class="model-active">
        <div class="key-status-row">
          <span class="badge success">当前生效</span>
          <span>{{ savedModel ? savedModelName : '系统默认模型' }}</span>
        </div>
        <p class="muted">
          {{
            savedModel
              ? '下一场面试/训练将使用该官方模型（仍按每日免费额度计次）。'
              : '下一场面试/训练将使用系统默认模型。'
          }}
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings-card h2 {
  margin-bottom: 8px;
}

.loading-text {
  margin-top: 16px;
}

.key-status {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: flex-start;
}

.key-status-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.key-status button {
  align-self: flex-start;
}

.key-form {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 520px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: 13px;
}

.field select {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 14px;
  background: #fff;
}

input.readonly {
  background: #f5f7fa;
  color: var(--text-light);
}

.hint {
  font-size: 12px;
}

.key-form button[type='submit'] {
  align-self: flex-start;
}

.model-action-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.saved-flash {
  color: var(--success);
  font-size: 14px;
  font-weight: 600;
  animation: saved-flash-in 0.25s ease-out;
}

@keyframes saved-flash-in {
  from {
    opacity: 0;
    transform: translateY(3px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.warning-text {
  color: var(--warning);
}

.model-active {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--border);
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-start;
}
</style>
