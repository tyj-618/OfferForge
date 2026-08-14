<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { askStream, interviewApi } from '../api'

const router = useRouter()
const SESSION_KEY = 'offerforge_session'

const phase = ref('idle') // idle | active | finishing
const position = ref('')
const sessionId = ref('')
const status = ref(null)
const messages = ref([])
const answer = ref('')
const sending = ref(false)
const error = ref('')

const chatBox = ref(null)

const progressText = computed(() => {
  if (!status.value) {
    return ''
  }
  return `${status.value.askedCount} / ${status.value.plannedTotal}`
})

const isFinished = computed(() => status.value?.state === 'FINISHED')

const difficultyClass = computed(() => {
  const label = status.value?.difficultyLabel
  if (label === '困难') return 'danger'
  if (label === '简单') return 'success'
  return ''
})

function scoreClass(score) {
  if (score >= 7) return 'success'
  if (score >= 4) return 'warning'
  return 'danger'
}

onMounted(async () => {
  // 刷新页面后恢复进行中的会话（状态栏与当前题；历史消息不回放）
  const saved = sessionStorage.getItem(SESSION_KEY)
  if (!saved) {
    return
  }
  try {
    status.value = await interviewApi.status(saved)
    sessionId.value = saved
    phase.value = 'active'
    if (isFinished.value) {
      messages.value.push({ role: 'assistant', content: '本次面试已结束，可前往「历史报告」查看或生成报告。' })
    } else if (status.value.currentQuestion) {
      messages.value.push({
        role: 'assistant',
        content: status.value.currentQuestion,
        restored: true
      })
    }
  } catch {
    sessionStorage.removeItem(SESSION_KEY)
  }
})

async function startInterview() {
  sending.value = true
  error.value = ''
  try {
    const data = await interviewApi.start(position.value.trim())
    sessionId.value = data.sessionId
    status.value = data.status
    sessionStorage.setItem(SESSION_KEY, data.sessionId)
    messages.value = [{ role: 'assistant', content: data.openingMessage }]
    phase.value = 'active'
  } catch (e) {
    error.value = e.message
  } finally {
    sending.value = false
  }
}

async function send(textOverride) {
  const text = (textOverride ?? answer.value).trim()
  if (!text || sending.value || isFinished.value) {
    return
  }
  sending.value = true
  error.value = ''
  messages.value.push({ role: 'user', content: text })
  const assistantMessage = { role: 'assistant', content: '' }
  messages.value.push(assistantMessage)
  answer.value = ''
  scrollDown()
  try {
    await askStream(sessionId.value, text, {
      onMessage: (chunk) => {
        assistantMessage.content += chunk
        scrollDown()
      },
      onDone: async (result) => {
        assistantMessage.score = result.score
        assistantMessage.comment = result.evaluationComment
        status.value = result.status
        if (result.action === 'FINISH' || result.status?.state === 'FINISHED') {
          await finishAndShowReport()
        }
      },
      onError: (e) => {
        error.value = e.message
        if (!assistantMessage.content) {
          messages.value.pop()
        }
      }
    })
  } catch (e) {
    error.value = e.message
    if (!assistantMessage.content) {
      messages.value.pop()
    }
  } finally {
    sending.value = false
    scrollDown()
  }
}

async function finishInterview() {
  await finishAndShowReport()
}

async function finishAndShowReport() {
  if (phase.value === 'finishing') {
    return
  }
  phase.value = 'finishing'
  try {
    // finish 触发报告生成与归档，随后跳转报告页
    await interviewApi.finish(sessionId.value)
    sessionStorage.removeItem(SESSION_KEY)
    router.push(`/report/${sessionId.value}`)
  } catch (e) {
    error.value = e.message
    phase.value = 'active'
  }
}

function scrollDown() {
  nextTick(() => {
    if (chatBox.value) {
      chatBox.value.scrollTop = chatBox.value.scrollHeight
    }
  })
}
</script>

<template>
  <div class="page">
    <h1 class="page-title">模拟面试</h1>

    <!-- 开始卡片 -->
    <div v-if="phase === 'idle'" class="card start-card">
      <h2>开始一场模拟面试</h2>
      <p class="muted">
        面试分为基础考察、项目经历、深度追问三个环节，AI 面试官会根据你的回答动态追问与调整难度，结束后生成详细反馈报告。
      </p>
      <form class="start-row" @submit.prevent="startInterview">
        <input v-model="position" placeholder="面试岗位方向，如：Java 后端工程师（可留空）" :disabled="sending" />
        <button type="submit" :disabled="sending">{{ sending ? '准备中…' : '开始面试' }}</button>
      </form>
      <p v-if="error" class="error-text">{{ error }}</p>
    </div>

    <!-- 进行中 -->
    <template v-else>
      <div class="card status-bar">
        <span class="badge">{{ status?.phaseLabel || '—' }}</span>
        <span :class="['badge', difficultyClass]">难度：{{ status?.difficultyLabel || '—' }}</span>
        <span
          v-if="status?.currentQuestionFollowUp"
          class="badge warning"
        >
          🔄 追问 {{ status?.followUpsUsed }}/{{ status?.followUpLimit }}
        </span>
        <span class="muted progress">进度 {{ progressText }} 题</span>
        <span v-if="status?.averageScore" class="muted">平均 {{ status.averageScore.toFixed(1) }} 分</span>
        <button v-if="!isFinished" class="ghost finish-btn" :disabled="phase === 'finishing'" @click="finishInterview">
          结束面试
        </button>
      </div>

      <div ref="chatBox" class="card chat-box">
        <div v-for="(item, index) in messages" :key="index" :class="['bubble-row', item.role]">
          <div class="bubble">
            <div class="bubble-content">{{ item.content }}<span v-if="item.restored" class="muted">（刷新恢复，历史消息不回放）</span></div>
            <div v-if="item.score != null" class="score-line">
              <span :class="['badge', scoreClass(item.score)]">得分 {{ item.score }}</span>
              <span v-if="item.comment" class="muted comment">{{ item.comment }}</span>
            </div>
          </div>
        </div>
        <p v-if="error" class="error-text">{{ error }}</p>
      </div>

      <form v-if="!isFinished" class="card answer-row" @submit.prevent="send()">
        <textarea
          v-model="answer"
          rows="3"
          placeholder="输入你的回答…（Ctrl+Enter 发送）"
          :disabled="sending"
          @keydown.ctrl.enter.prevent="send()"
        ></textarea>
        <button type="submit" :disabled="sending || !answer.trim()">
          {{ sending ? '面试官思考中…' : '发送' }}
        </button>
      </form>
    </template>
  </div>
</template>

<style scoped>
.start-card h2 {
  margin-bottom: 8px;
}

.start-row {
  display: flex;
  gap: 10px;
  margin-top: 16px;
}

.status-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  margin-bottom: 12px;
}

.progress {
  margin-left: auto;
}

.finish-btn {
  margin-left: 8px;
}

.chat-box {
  height: 460px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.bubble-row {
  display: flex;
}

.bubble-row.user {
  justify-content: flex-end;
}

.bubble {
  max-width: 78%;
  padding: 10px 14px;
  border-radius: 12px;
}

.bubble-row.user .bubble {
  background: var(--primary);
  color: #fff;
}

.bubble-row.assistant .bubble {
  background: #f1f3f9;
}

.bubble-content {
  white-space: pre-wrap;
  word-break: break-word;
}

.score-line {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.comment {
  font-size: 12px;
}

.answer-row {
  display: flex;
  gap: 10px;
  margin-top: 12px;
  align-items: flex-end;
}

.answer-row button {
  height: 42px;
}
</style>
