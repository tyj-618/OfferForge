<script setup>
import { ref } from 'vue'
import { knowledgeApi, qaApi } from '../api'

const importSummary = ref(null)
const importing = ref(false)
const importError = ref('')

const question = ref('')
const asking = ref(false)
const askError = ref('')
const conversations = ref([])

async function importKnowledge() {
  importing.value = true
  importError.value = ''
  try {
    importSummary.value = await knowledgeApi.importBuiltin()
  } catch (e) {
    importError.value = e.message
  } finally {
    importing.value = false
  }
}

async function ask() {
  const text = question.value.trim()
  if (!text || asking.value) {
    return
  }
  asking.value = true
  askError.value = ''
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
    askError.value = e.message
    conversations.value.pop()
  } finally {
    asking.value = false
  }
}
</script>

<template>
  <div class="page">
    <h1 class="page-title">问答练习</h1>

    <div class="card knowledge-card">
      <div class="knowledge-row">
        <div>
          <strong>知识库</strong>
          <p class="muted">问答与模拟面试基于你的个人知识库出题，首次使用请先导入内置题库。</p>
        </div>
        <button :disabled="importing" @click="importKnowledge">
          {{ importing ? '导入中…' : '导入内置知识库' }}
        </button>
      </div>
      <p v-if="importSummary" class="muted import-result">
        ✅ 导入完成：共 {{ importSummary.total }} 条，新增 {{ importSummary.inserted }} 条，跳过
        {{ importSummary.skipped }} 条（已存在）
      </p>
      <p v-if="importError" class="error-text">{{ importError }}</p>
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
      <p v-if="askError" class="error-text">{{ askError }}</p>

      <form class="ask-row" @submit.prevent="ask">
        <input v-model="question" placeholder="例如：HashMap 的底层原理是什么？" :disabled="asking" />
        <button type="submit" :disabled="asking || !question.trim()">
          {{ asking ? '思考中…' : '提问' }}
        </button>
      </form>
    </div>
  </div>
</template>

<style scoped>
.knowledge-card {
  margin-bottom: 16px;
}

.knowledge-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
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
