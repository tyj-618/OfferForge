<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { apiKeyApi } from '../api'
import { notifyError } from '../utils/errors'
import { toast } from '../toast'

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
</style>
