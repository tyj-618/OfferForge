<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { marked } from 'marked'
import { knowledgeApi } from '../api'
import { notifyError } from '../utils/errors'
import {
  qaSession,
  restoreQaSession,
  clearQaSession,
  sendQaQuestion,
  retryQaAt,
  isOverLimit
} from '../store/qaSession'
import { toast } from '../toast'

// 回答含 Markdown（列表/代码块等），与面试页同款渲染配置
marked.use({ gfm: true, breaks: true })

function renderMarkdown(content) {
  if (!content) {
    return ''
  }
  return marked.parse(content)
}

const question = ref('')
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

onMounted(() => {
  restoreQaSession()
  loadCounts()
})

// 新消息/流式内容更新时自动滚到底部，保证最新内容可见
watch(
  () => [qaSession.conversations.length, qaSession.conversations.at(-1)?.content, qaSession.asking],
  () => {
    nextTick(() => {
      const el = chatScroll.value
      if (el) {
        el.scrollTop = el.scrollHeight
      }
    })
  }
)

const overLimit = computed(() => isOverLimit() && !qaSession.asking)

function ask(textOverride) {
  const text = (textOverride ?? question.value).trim()
  if (!text || qaSession.asking) {
    return
  }
  question.value = ''
  sendQaQuestion(text)
}

// 清空会话确认：应用内居中模态窗（替代浏览器原生 confirm，保持 UI 风格一致）
const clearConfirmVisible = ref(false)

function clearSession() {
  if (qaSession.conversations.length === 0) {
    return
  }
  clearConfirmVisible.value = true
}

function confirmClear() {
  clearConfirmVisible.value = false
  clearQaSession()
  toast.success('会话已清空')
}
</script>

<template>
  <div class="page">
    <h1 class="page-title">快捷提问</h1>

    <div class="card knowledge-card">
      <p class="muted knowledge-hint">
        回答默认参考资源库全部资料：官方题库 {{ officialCount }} 条 + 我的资料 {{ myCount }} 条
      </p>
      <button
        type="button"
        class="ghost clear-btn"
        :disabled="qaSession.conversations.length === 0"
        @click="clearSession"
      >
        🗑 清空会话
      </button>
    </div>

    <div class="card qa-card">
      <div ref="chatScroll" class="chat-scroll">
        <div v-if="qaSession.conversations.length === 0" class="empty">
          向 AI 教练提问任何技术面试题，回答将基于你的知识库生成；切换页面后对话仍会保留
        </div>
        <div
          v-for="(item, index) in qaSession.conversations"
          :key="index"
          :class="['bubble-row', item.role]"
        >
          <div class="bubble">
            <template v-if="item.error">
              <div class="bubble-content error-text">⚠️ {{ item.error }}</div>
              <button type="button" class="ghost retry-btn" :disabled="qaSession.asking" @click="retryQaAt(index)">
                重试
              </button>
            </template>
            <template v-else>
              <div v-if="item.role === 'assistant'" class="bubble-content md" v-html="renderMarkdown(item.content)"></div>
              <div v-else class="bubble-content">{{ item.content }}</div>
              <span v-if="item.streaming" class="stream-cursor"></span>
              <div v-if="item.refs && item.refs.length" class="muted refs">
                引用知识条目：{{ item.refs.join(', ') }}
              </div>
            </template>
          </div>
        </div>
        <div v-if="qaSession.asking && qaSession.conversations.at(-1)?.content === ''" class="bubble-row assistant">
          <div class="bubble typing-bubble" aria-label="AI 教练思考中">
            <span class="typing-dot"></span><span class="typing-dot"></span><span class="typing-dot"></span>
          </div>
        </div>
      </div>

      <p v-if="overLimit" class="limit-hint">
        对话已达长度上限，请点击右上角「清空会话」删除历史后再继续提问
      </p>

      <form class="ask-row" @submit.prevent="ask()">
        <input
          v-model="question"
          placeholder="例如：HashMap 的底层原理是什么？"
          :disabled="qaSession.asking || overLimit"
        />
        <button type="submit" :disabled="qaSession.asking || overLimit || !question.trim()">
          {{ qaSession.asking ? '回答中…' : '提问' }}
        </button>
      </form>
    </div>

    <!-- 清空会话确认弹窗：全屏遮罩 + 屏幕居中卡片 -->
    <div v-if="clearConfirmVisible" class="modal-overlay" @click.self="clearConfirmVisible = false">
      <div class="modal-card" role="dialog" aria-modal="true" aria-label="清空会话确认">
        <h3>清空会话</h3>
        <p class="muted">
          确定清空全部对话历史吗？共 {{ qaSession.conversations.length }} 条消息，
          清除后无法恢复。
        </p>
        <div class="modal-actions">
          <button type="button" class="ghost" @click="clearConfirmVisible = false">取消</button>
          <button type="button" class="danger" @click="confirmClear">确认清空</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.knowledge-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
  padding: 12px 20px;
}

.knowledge-hint {
  margin: 0;
}

.clear-btn {
  flex-shrink: 0;
}

/* 清空会话确认弹窗：遮罩全屏，卡片屏幕居中 */
.modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(15, 23, 42, 0.45);
}

.modal-card {
  width: min(420px, calc(100vw - 48px));
  padding: 22px 24px;
  background: var(--card, #fff);
  border: 1px solid var(--border, #e4e8f0);
  border-radius: var(--radius, 12px);
  box-shadow: 0 12px 32px rgba(15, 23, 42, 0.18);
}

.modal-card h3 {
  margin: 0 0 10px;
  font-size: 16px;
}

.modal-card p {
  margin: 0 0 18px;
  line-height: 1.6;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.modal-actions .danger {
  background: #ef4444;
  border: 1px solid #ef4444;
  color: #fff;
}

.modal-actions .danger:hover {
  background: #dc2626;
}

/* 聊天区高度钉在视口内：进入页面即可看到输入框，历史消息在内部滚动 */
.qa-card {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 235px);
  min-height: 380px;
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
  max-width: 85%;
  padding: 10px 14px;
  border-radius: 12px;
  word-break: break-word;
}

.bubble-row.user .bubble {
  background: var(--primary);
  color: #fff;
}

.bubble-row.user .bubble-content {
  white-space: pre-wrap;
}

.bubble-row.assistant .bubble {
  background: #f1f3f9;
}

/* Markdown 渲染：压缩段落间距，适配气泡场景 */
.bubble :deep(.md p) {
  margin: 0 0 8px;
}

.bubble :deep(.md p:last-child) {
  margin-bottom: 0;
}

.bubble :deep(.md pre) {
  background: #272b33;
  color: #e6e6e6;
  padding: 10px 12px;
  border-radius: 8px;
  overflow-x: auto;
}

.bubble :deep(.md code) {
  background: rgba(0, 0, 0, 0.08);
  padding: 1px 4px;
  border-radius: 4px;
}

.bubble :deep(.md pre code) {
  background: none;
  padding: 0;
}

.bubble :deep(.md ul),
.bubble :deep(.md ol) {
  margin: 4px 0 8px;
  padding-left: 20px;
}

.error-text {
  color: #b42318;
}

.retry-btn {
  margin-top: 6px;
  padding: 2px 10px;
}

.refs {
  margin-top: 6px;
  font-size: 12px;
}

.stream-cursor {
  display: inline-block;
  width: 8px;
  height: 14px;
  margin-left: 2px;
  background: var(--primary);
  vertical-align: text-bottom;
  animation: qa-cursor-blink 1s steps(1) infinite;
}

@keyframes qa-cursor-blink {
  50% {
    opacity: 0;
  }
}

.limit-hint {
  margin: 8px 0 0;
  color: #b42318;
  font-size: 13px;
}

.ask-row {
  display: flex;
  gap: 10px;
  padding-top: 16px;
}
</style>
