<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  knowledgeApi,
  trainingApi,
  trainingAnswerStream,
  quotaApi
} from '../api'
import { classifyError } from '../utils/errors'
import { toast } from '../toast'

const SESSION_KEY = 'offerforge_training_session'

const route = useRoute()
const router = useRouter()
// 任务 4：由模拟面试「深入该模块」跳转而来，训练结束后引导回面试页续考
const fromInterview = route.query.from === 'interview'

const phase = ref('select') // select | active | summary
const categoryOptions = ref([])
const records = ref([])
const quotaInfo = ref(null)
const error = ref('')

const sessionId = ref('')
const status = ref(null)
const messages = ref([])
const answer = ref('')
const sending = ref(false)
const thinkingText = ref('')
const chatBox = ref(null)

// 「具体分析」小窗：同一时刻最多展开一个，锚定在对应得分徽章旁
const analysisOpenIndex = ref(null)

function toggleAnalysis(index) {
  analysisOpenIndex.value = analysisOpenIndex.value === index ? null : index
}

const difficultyLabels = { EASY: '简单', MEDIUM: '中等', HARD: '困难' }

function difficultyLabel(name) {
  return difficultyLabels[name] || name || '—'
}

const progressPercent = computed(() => {
  const max = status.value?.maxQuestions || 0
  return max ? Math.min(100, (status.value.askedCount / max) * 100) : 0
})

function scoreClass(score) {
  if (score >= 7) return 'success'
  if (score >= 4) return 'warning'
  return 'danger'
}

onMounted(async () => {
  loadCategories()
  loadRecords()
  refreshQuota()
  // 刷新页面后恢复进行中的训练会话（历史消息不回放，展示当前题）
  const saved = sessionStorage.getItem(SESSION_KEY)
  if (saved) {
    try {
      const restored = await trainingApi.status(saved)
      if (restored?.finished) {
        sessionStorage.removeItem(SESSION_KEY)
      } else {
        sessionId.value = saved
        status.value = restored
        phase.value = 'active'
        messages.value = [{
          role: 'assistant',
          content: restored.currentQuestion || '欢迎回来，请继续作答当前题目。',
          restored: true
        }]
        scrollDown()
        return
      }
    } catch {
      sessionStorage.removeItem(SESSION_KEY)
    }
  }
  // 任务 4：带目标分组跳转而来（面试「深入该模块」）：自动开启该分组专项训练
  const targetCategory = route.query.category
  if (typeof targetCategory === 'string' && targetCategory.trim()) {
    startTraining(targetCategory.trim())
  }
})

async function loadCategories() {
  try {
    const view = await knowledgeApi.categories()
    categoryOptions.value = [
      ...(view?.official || []).map((name) => ({ name, official: true })),
      ...(view?.custom || []).map((name) => ({ name, official: false }))
    ]
  } catch {
    // 分组加载失败提示即可，不阻断页面
  }
}

async function loadRecords() {
  try {
    records.value = (await trainingApi.records()).slice(0, 5)
  } catch {
    records.value = []
  }
}

async function refreshQuota() {
  try {
    quotaInfo.value = await quotaApi.get()
  } catch {
    // 额度信息加载失败不阻断训练开始（后端仍会校验）
  }
}

async function startTraining(category) {
  error.value = ''
  sending.value = true
  try {
    const data = await trainingApi.start(category)
    sessionId.value = data.sessionId
    status.value = data.status
    sessionStorage.setItem(SESSION_KEY, data.sessionId)
    messages.value = [{ role: 'assistant', content: data.openingMessage }]
    phase.value = 'active'
    refreshQuota()
    scrollDown()
  } catch (e) {
    if (e.code === 'QUOTA_EXCEEDED') {
      error.value = '今日免费额度已用完，可前往「设置」配置自己的 API Key 继续使用'
      toast.error(e.message || '今日免费额度已用完')
      refreshQuota()
    } else {
      error.value = classifyError(e).message
    }
  } finally {
    sending.value = false
  }
}

// 提交作答：SSE 流式渲染导师点评与下一题，done 帧携带评分与详细评估
async function submitAnswer() {
  const text = answer.value.trim()
  if (!text || sending.value || !sessionId.value || status.value?.finished) {
    return
  }
  sending.value = true
  error.value = ''
  answer.value = ''
  messages.value.push({ role: 'user', content: text })
  const assistantMessage = { role: 'assistant', content: '' }
  messages.value.push(assistantMessage)
  // 当前承接流式内容的气泡：segment 帧后重指向新气泡
  let currentBubble = assistantMessage
  thinkingText.value = '正在评估你的回答…'
  scrollDown()
  try {
    await trainingAnswerStream(sessionId.value, text, {
      onMessage: (chunk) => {
        thinkingText.value = ''
        currentBubble.content += chunk
        scrollDown()
      },
      onSegment: () => {
        const bubble = { role: 'assistant', content: '' }
        messages.value.push(bubble)
        currentBubble = bubble
      },
      onProgress: (text2) => {
        thinkingText.value = text2
      },
      onDone: (result) => {
        attachScore(result)
        status.value = result.status
        if (result.finished) {
          endSession()
        }
        sending.value = false
        thinkingText.value = ''
        scrollDown()
      },
      onError: (e) => failStream(assistantMessage, e)
    })
  } catch (e) {
    failStream(assistantMessage, e)
  } finally {
    if (sending.value && !thinkingText.value) {
      // 连接结束但无 done 事件：释放输入状态
      sending.value = false
    }
    scrollDown()
  }
}

function attachScore(result) {
  // 得分徽章挂在最近一个有内容的气泡上（导师点评气泡），附带详细评估供「具体分析」展开
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const item = messages.value[i]
    if (item.role === 'assistant' && item.content && item.score == null) {
      item.score = result.score
      item.evaluation = result.evaluation || null
      break
    }
  }
}

function failStream(assistantMessage, e) {
  sending.value = false
  thinkingText.value = ''
  const classified = classifyError(e)
  if (assistantMessage.content) {
    assistantMessage.comment = '连接已中断，请刷新页面查看最新进度'
  } else {
    messages.value.pop()
  }
  error.value = classified.message
  toast.error(classified.message)
}

function endSession() {
  sessionStorage.removeItem(SESSION_KEY)
  phase.value = 'summary'
  loadRecords()
}

// 主动结束训练：归档已作答成绩后展示成绩卡
async function quitTraining() {
  if (sending.value) {
    return
  }
  sending.value = true
  try {
    const result = await trainingApi.finish(sessionId.value)
    status.value = result
    endSession()
  } catch (e) {
    error.value = classifyError(e).message
  } finally {
    sending.value = false
  }
}

function backToSelect() {
  phase.value = 'select'
  sessionId.value = ''
  status.value = null
  messages.value = []
  error.value = ''
  loadCategories()
  loadRecords()
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
  <div class="page training-page">
    <h1 class="page-title">专项训练</h1>

    <!-- 分组选择 -->
    <div v-if="phase === 'select'" class="card select-card">
      <p class="muted">
        选择一个资料分组开启专项训练：由浅入深出题（简单 → 中等 → 困难），
        每题作答后有导师点评与得分，连续高分自动升难度，完成全部题目后归档成绩。
      </p>
      <div v-if="quotaInfo && !quotaInfo.hasKey" class="quota-hint muted">
        <template v-if="quotaInfo.remaining > 0">今日剩余免费额度：{{ quotaInfo.remaining }} 次（开始训练消耗 1 次）</template>
        <template v-else>今日免费额度已用完，可前往「设置」配置自己的 API Key 继续使用</template>
      </div>
      <div v-if="categoryOptions.length" class="category-grid">
        <button
          v-for="opt in categoryOptions"
          :key="opt.name"
          type="button"
          class="category-card"
          :disabled="sending"
          @click="startTraining(opt.name)"
        >
          <span class="category-name">{{ opt.name }}</span>
          <span class="muted category-tag">{{ opt.official ? '官方题库' : '我的资料' }}</span>
        </button>
      </div>
      <p v-else class="muted">暂无可用分组，请先前往 <RouterLink to="/knowledge">资料库</RouterLink> 导入官方题库或上传资料。</p>
      <p v-if="error" class="error-text">{{ error }}</p>

      <div v-if="records.length" class="recent-records">
        <h3>最近训练</h3>
        <div v-for="record in records" :key="record.id" class="record-row">
          <span>{{ record.category }}</span>
          <span class="muted">{{ record.askedCount }} 题 · 最高难度 {{ difficultyLabel(record.maxDifficulty) }}</span>
          <span :class="['badge', scoreClass(record.averageScore / 10)]">{{ record.averageScore.toFixed(1) }} 分</span>
        </div>
      </div>
    </div>

    <!-- 训练中 -->
    <template v-else-if="phase === 'active'">
      <div v-if="fromInterview" class="from-interview-hint">
        🎯 来自模拟面试的深入训练：面试进度已暂存，完成训练后回「模拟面试」页可继续未完成的面试。
      </div>
      <div class="training-header card">
        <div class="header-line">
          <span class="badge">{{ status?.category }}</span>
          <span class="badge warning">当前难度：{{ difficultyLabel(status?.currentDifficulty) }}</span>
          <span class="muted">已完成 {{ status?.askedCount || 0 }} / {{ status?.maxQuestions || 0 }} 题</span>
          <button class="ghost quit-btn" :disabled="sending" @click="quitTraining">结束训练</button>
        </div>
        <div class="progress-track">
          <div class="progress-fill" :style="{ width: progressPercent + '%' }"></div>
        </div>
      </div>

      <div ref="chatBox" class="card chat-box">
        <div v-for="(item, index) in messages" :key="index" :class="['bubble-row', item.role]">
          <div class="bubble">
            <div class="bubble-content">{{ item.content }}<span v-if="item.restored" class="muted">（刷新恢复，历史消息不回放）</span></div>
            <span v-if="item.comment" class="muted comment">{{ item.comment }}</span>
            <div v-if="item.score != null" class="score-line">
              <span :class="['badge', scoreClass(item.score)]">得分 {{ item.score }}</span>
              <div v-if="item.evaluation" class="analysis-anchor">
                <button type="button" class="ghost small analysis-btn" @click="toggleAnalysis(index)">
                  {{ analysisOpenIndex === index ? '收起分析' : '具体分析' }}
                </button>
                <div v-if="analysisOpenIndex === index" class="analysis-pop">
                  <div v-if="item.evaluation.goodPoints?.length">
                    <strong>亮点：</strong>
                    <ul><li v-for="point in item.evaluation.goodPoints" :key="point">{{ point }}</li></ul>
                  </div>
                  <div v-if="item.evaluation.badPoints?.length">
                    <strong>不足：</strong>
                    <ul><li v-for="point in item.evaluation.badPoints" :key="point">{{ point }}</li></ul>
                  </div>
                  <div v-if="item.evaluation.improvedAnswer">
                    <strong>改进后的回答：</strong>
                    <p>{{ item.evaluation.improvedAnswer }}</p>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
        <div v-if="thinkingText" class="thinking muted">{{ thinkingText }}</div>
      </div>

      <div class="input-row">
        <textarea
          v-model="answer"
          class="answer-input"
          rows="3"
          placeholder="输入你的回答…（Ctrl+Enter 发送）"
          :disabled="sending || status?.finished"
          @keydown.ctrl.enter="submitAnswer"
        ></textarea>
        <button :disabled="sending || !answer.trim() || status?.finished" @click="submitAnswer">
          {{ sending ? '评估中…' : '提交回答' }}
        </button>
      </div>
      <p v-if="error" class="error-text">{{ error }}</p>
    </template>

    <!-- 完成成绩 -->
    <div v-else class="card summary-card">
      <h2>🎉 专项训练完成</h2>
      <div class="summary-grid">
        <div class="summary-item">
          <span class="summary-value">{{ status?.averageScore ?? '—' }}</span>
          <span class="muted">平均得分</span>
        </div>
        <div class="summary-item">
          <span class="summary-value">{{ status?.askedCount ?? 0 }}</span>
          <span class="muted">作答题数</span>
        </div>
        <div class="summary-item">
          <span class="summary-value">{{ difficultyLabel(status?.maxDifficultyReached) }}</span>
          <span class="muted">最高难度</span>
        </div>
      </div>
      <p class="muted">成绩已归档，可在本页「最近训练」中查看历史。</p>
      <div class="summary-actions">
        <button v-if="fromInterview" :disabled="sending" @click="router.push('/interview')">← 返回模拟面试继续考试</button>
        <button :class="{ secondary: fromInterview }" :disabled="sending" @click="backToSelect">再来一轮</button>
        <button class="secondary" :disabled="sending" @click="startTraining(status?.category)">再练「{{ status?.category }}」</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.training-page {
  max-width: 860px;
  margin: 0 auto;
}

.select-card {
  padding: 20px;
}

.from-interview-hint {
  margin-bottom: 10px;
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 13px;
  background: #fff7e6;
  border: 1px solid #ffe3ad;
}

.quota-hint {
  margin: 10px 0;
}

.category-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 12px;
  margin-top: 14px;
}

.category-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  padding: 14px;
  border: 1px solid var(--border, #e3e6ef);
  border-radius: 10px;
  background: var(--bg-card, #fff);
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.category-card:hover:not(:disabled) {
  border-color: var(--primary, #4f6ef7);
  box-shadow: 0 2px 8px rgba(79, 110, 247, 0.15);
}

.category-name {
  font-weight: 600;
}

.category-tag {
  font-size: 12px;
}

.recent-records {
  margin-top: 22px;
  border-top: 1px solid var(--border, #e3e6ef);
  padding-top: 14px;
}

.record-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 0;
  font-size: 14px;
}

.record-row .muted {
  flex: 1;
}

.training-header {
  padding: 12px 16px;
  margin-bottom: 12px;
}

.header-line {
  display: flex;
  align-items: center;
  gap: 10px;
}

.quit-btn {
  margin-left: auto;
}

.progress-track {
  height: 6px;
  border-radius: 3px;
  background: var(--border, #e3e6ef);
  margin-top: 10px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  border-radius: 3px;
  background: var(--primary, #4f6ef7);
  transition: width 0.3s;
}

.chat-box {
  max-height: 52vh;
  overflow-y: auto;
  padding: 16px;
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
  max-width: 80%;
  padding: 10px 14px;
  border-radius: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.bubble-row.assistant .bubble {
  background: var(--bg-soft, #f4f6fb);
}

.bubble-row.user .bubble {
  background: var(--primary, #4f6ef7);
  color: #fff;
}

.comment {
  display: block;
  margin-top: 6px;
  font-size: 12px;
}

.score-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.analysis-anchor {
  position: relative;
}

.analysis-btn {
  font-size: 12px;
}

.analysis-pop {
  position: absolute;
  z-index: 20;
  top: calc(100% + 6px);
  left: 0;
  width: min(420px, 80vw);
  max-height: 320px;
  overflow-y: auto;
  background: var(--bg-card, #fff);
  border: 1px solid var(--border, #e3e6ef);
  border-radius: 10px;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.12);
  padding: 12px 14px;
  font-size: 13px;
  line-height: 1.7;
  text-align: left;
  white-space: normal;
}

.analysis-pop ul {
  margin: 4px 0 8px 18px;
  padding: 0;
}

.thinking {
  font-size: 13px;
  padding-left: 4px;
}

.input-row {
  display: flex;
  gap: 10px;
  margin-top: 12px;
  align-items: stretch;
}

.answer-input {
  flex: 1;
  resize: none;
}

.summary-card {
  padding: 28px;
  text-align: center;
}

.summary-grid {
  display: flex;
  justify-content: center;
  gap: 48px;
  margin: 22px 0 12px;
}

.summary-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.summary-value {
  font-size: 30px;
  font-weight: 700;
  color: var(--primary, #4f6ef7);
}

.summary-actions {
  display: flex;
  justify-content: center;
  gap: 12px;
  margin-top: 18px;
}
</style>
