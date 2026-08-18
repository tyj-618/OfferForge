<script setup>
import { onMounted, ref } from 'vue'
import { knowledgeApi, qaApi } from '../api'
import { notifyError } from '../utils/errors'
import { toast } from '../toast'
import UploadModal from '../components/UploadModal.vue'

const importSummary = ref(null)
const importing = ref(false)

const question = ref('')
const asking = ref(false)
const conversations = ref([])

// 知识库构成：官方题库 + 本人上传资料，两者都会参与快捷提问检索
const officialCount = ref(0)
const myCount = ref(0)
const uploadOpen = ref(false)
const customCategoryList = ref([])

async function loadCounts() {
  try {
    const [official, mine, view] = await Promise.all([
      knowledgeApi.official(),
      knowledgeApi.mine(),
      knowledgeApi.categories()
    ])
    officialCount.value = official?.length || 0
    myCount.value = mine?.length || 0
    customCategoryList.value = view?.custom || []
  } catch (e) {
    notifyError(e)
  }
}

onMounted(loadCounts)

async function importKnowledge() {
  importing.value = true
  try {
    importSummary.value = await knowledgeApi.importBuiltin()
    toast.success(`官方题库导入完成：新增 ${importSummary.value.inserted} 条，已存在 ${importSummary.value.skipped} 条`)
    await loadCounts()
  } catch (e) {
    notifyError(e, importKnowledge)
  } finally {
    importing.value = false
  }
}

// 上传成功后刷新知识库计数
function onUploaded() {
  importSummary.value = null
  loadCounts()
}

async function ask(textOverride) {
  const text = (textOverride ?? question.value).trim()
  if (!text || asking.value) {
    return
  }
  asking.value = true
  conversations.value.push({ role: 'user', content: text })
  question.value = ''
  try {
    const data = await qaApi.ask(text)
    conversations.value.push({
      role: 'assistant',
      content: data.answer,
      refs: data.referencedKnowledgeIds || []
    })
  } catch (e) {
    conversations.value.pop()
    notifyError(e, () => ask(text))
  } finally {
    asking.value = false
  }
}
</script>

<template>
  <div class="page">
    <h1 class="page-title">快捷提问</h1>

    <div class="card knowledge-card">
      <div class="knowledge-row">
        <div>
          <strong>知识库</strong>
          <p class="muted">
            快捷提问基于你的知识库作答：官方题库 {{ officialCount }} 条 + 我的资料 {{ myCount }} 条，
            两者都会参与检索。
          </p>
        </div>
        <div class="knowledge-actions">
          <button :disabled="importing" @click="importKnowledge">
            {{ importing ? '导入中…' : '导入官方题库' }}
          </button>
          <button type="button" @click="uploadOpen = true">上传我的资料</button>
        </div>
      </div>
      <p v-if="importSummary" class="muted import-result">
        ✅ 导入完成：共 {{ importSummary.total }} 条，新增 {{ importSummary.inserted }} 条，跳过
        {{ importSummary.skipped }} 条（已存在）
      </p>
    </div>

    <div class="card qa-card">
      <div v-if="conversations.length === 0" class="empty">
        向 AI 教练提问任何技术面试题，回答将基于你的知识库生成
      </div>
      <div v-for="(item, index) in conversations" :key="index" :class="['bubble-row', item.role]">
        <div class="bubble">
          <div class="bubble-content">{{ item.content }}</div>
          <div v-if="item.refs && item.refs.length" class="muted refs">
            引用知识条目：{{ item.refs.join(', ') }}
          </div>
        </div>
      </div>
      <div v-if="asking" class="bubble-row assistant">
        <div class="bubble typing-bubble" aria-label="AI 教练思考中">
          <span class="typing-dot"></span><span class="typing-dot"></span><span class="typing-dot"></span>
        </div>
      </div>

      <form class="ask-row" @submit.prevent="ask()">
        <input v-model="question" placeholder="例如：HashMap 的底层原理是什么？" :disabled="asking" />
        <button type="submit" :disabled="asking || !question.trim()">
          {{ asking ? '思考中…' : '提问' }}
        </button>
      </form>
    </div>

    <!-- 上传我的资料小窗 -->
    <UploadModal
      v-if="uploadOpen"
      :custom-categories="customCategoryList"
      @close="uploadOpen = false"
      @uploaded="onUploaded"
    />
  </div>
</template>

<style scoped>
.knowledge-card {
  margin-bottom: 16px;
}

.knowledge-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.knowledge-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.import-result {
  margin-top: 10px;
}

.qa-card {
  display: flex;
  flex-direction: column;
  min-height: 420px;
}

.bubble-row {
  display: flex;
  margin-bottom: 12px;
}

.bubble-row.user {
  justify-content: flex-end;
}

.bubble {
  max-width: 75%;
  padding: 10px 14px;
  border-radius: 12px;
  white-space: pre-wrap;
  word-break: break-word;
}

.bubble-row.user .bubble {
  background: var(--primary);
  color: #fff;
}

.bubble-row.assistant .bubble {
  background: #f1f3f9;
}

.refs {
  margin-top: 6px;
  font-size: 12px;
}

.ask-row {
  display: flex;
  gap: 10px;
  margin-top: auto;
  padding-top: 16px;
}
</style>
