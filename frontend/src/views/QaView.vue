<script setup>
import { nextTick, onMounted, ref, watch } from 'vue'
import { knowledgeApi, qaApi } from '../api'
import { notifyError } from '../utils/errors'

const question = ref('')
const asking = ref(false)
const conversations = ref([])
const chatScroll = ref(null)

// 知识库构成：官方题库 + 本人上传资料，快捷提问默认参考资源库全部资料作答
const officialCount = ref(0)
const myCount = ref(0)

async function loadCounts() {
  try {
    const [official, mine] = await Promise.all([
      knowledgeApi.official(),
      knowledgeApi.mine()
    ])
    officialCount.value = official?.length || 0
    myCount.value = mine?.length || 0
  } catch (e) {
    notifyError(e)
  }
}

onMounted(loadCounts)

// 新消息/思考中气泡出现后自动滚到底部，保证最新内容可见
watch([conversations, asking], () => {
  nextTick(() => {
    const el = chatScroll.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
})

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
      <p class="muted knowledge-hint">
        回答默认参考资源库全部资料：官方题库 {{ officialCount }} 条 + 我的资料 {{ myCount }} 条
      </p>
    </div>

    <div class="card qa-card">
      <div ref="chatScroll" class="chat-scroll">
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
      </div>

      <form class="ask-row" @submit.prevent="ask()">
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
  padding: 12px 20px;
}

.knowledge-hint {
  margin: 0;
}

/* 聊天区高度钉在视口内：进入页面即可看到输入框，历史消息在内部滚动 */
.qa-card {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 300px);
  min-height: 300px;
  overflow: hidden;
}

.chat-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
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
  padding-top: 16px;
}
</style>
