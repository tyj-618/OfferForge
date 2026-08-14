<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { askStream, interviewApi, resumeApi, skipStream } from '../api'
import { classifyError, notifyError } from '../utils/errors'

const router = useRouter()
const SESSION_KEY = 'offerforge_session'

const phase = ref('idle') // idle | active | finishing
const position = ref('')
const sessionId = ref('')
const status = ref(null)
const messages = ref([])
const answer = ref('')
const sending = ref(false)
const thinking = ref(false)
const error = ref('')

const resumes = ref([])
const selectedResumeId = ref(null)

const chatBox = ref(null)

const confirmFinish = ref(false)
const phaseBanner = ref('')
const statusSheetOpen = ref(false)
let phaseBannerTimer = null

// 顶部进度条阶段定义，与后端状态机环节对齐
const STAGES = ['OPENING', 'BASICS', 'PROJECT', 'DEEP', 'CLOSING']

const stageIndex = computed(() => {
  const index = STAGES.indexOf(status.value?.state)
  return index < 0 ? STAGES.length - 1 : index
})

const stagePercent = computed(() => ((stageIndex.value + 1) / STAGES.length) * 100)

// 仅出题环节可跳过；开场（自我介绍）与收尾环节必须正常作答
const canSkip = computed(() => ['BASICS', 'PROJECT', 'DEEP'].includes(status.value?.state))

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

// 阶段切换（如基础题 → 项目题）：淡入淡出横幅提示阶段名
watch(
  () => status.value?.phaseLabel,
  (label, oldLabel) => {
    if (label && oldLabel && label !== oldLabel) {
      phaseBanner.value = label
      clearTimeout(phaseBannerTimer)
      phaseBannerTimer = setTimeout(() => {
        phaseBanner.value = ''
      }, 1800)
    }
  }
)

function isStreamingItem(item) {
  return (
    sending.value &&
    item.role === 'assistant' &&
    item === messages.value[messages.value.length - 1] &&
    item.score == null
  )
}

onMounted(async () => {
  // 开始卡片展示简历选择：一份自动选中，多份下拉选择
  try {
    resumes.value = await resumeApi.list()
    if (resumes.value.length === 1) {
      selectedResumeId.value = resumes.value[0].id
    }
  } catch {
    // 简历列表加载失败不阻断面试开始（后端会降级为通用项目题）
  }
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
    const data = await interviewApi.start(position.value.trim(), selectedResumeId.value || null)
    sessionId.value = data.sessionId
    status.value = data.status
    sessionStorage.setItem(SESSION_KEY, data.sessionId)
    messages.value = [{ role: 'assistant', content: data.openingMessage }]
    phase.value = 'active'
  } catch (e) {
    error.value = classifyError(e).message
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
  thinking.value = true
  messages.value.push({ role: 'user', content: text })
  const assistantMessage = { role: 'assistant', content: '' }
  messages.value.push(assistantMessage)
  answer.value = ''
  scrollDown()
  try {
    await askStream(sessionId.value, text, {
      onMessage: (chunk) => {
        thinking.value = false
        assistantMessage.content += chunk
        scrollDown()
      },
      onDone: (result) => handleAskDone(assistantMessage, result),
      onError: (e) => {
        if (!assistantMessage.content) {
          messages.value.pop()
        }
        notifyError(e, () => send(text))
      }
    })
  } catch (e) {
    if (!assistantMessage.content) {
      messages.value.pop()
    }
    notifyError(e, () => send(text))
  } finally {
    thinking.value = false
    sending.value = false
    scrollDown()
  }
}

// 跳过当前题：后端计 0 分并推进状态机，SSE 返回下一题
async function skipQuestion() {
  if (!canSkip.value || sending.value || isFinished.value) {
    return
  }
  sending.value = true
  thinking.value = true
  const assistantMessage = { role: 'assistant', content: '' }
  messages.value.push(assistantMessage)
  scrollDown()
  try {
    await skipStream(sessionId.value, {
      onMessage: (chunk) => {
        thinking.value = false
        assistantMessage.content += chunk
        scrollDown()
      },
      onDone: (result) => handleAskDone(assistantMessage, result),
      onError: (e) => {
        if (!assistantMessage.content) {
          messages.value.pop()
        }
        notifyError(e, skipQuestion)
      }
    })
  } catch (e) {
    if (!assistantMessage.content) {
      messages.value.pop()
    }
    notifyError(e, skipQuestion)
  } finally {
    thinking.value = false
    sending.value = false
    scrollDown()
  }
}

async function handleAskDone(assistantMessage, result) {
  assistantMessage.score = result.score
  assistantMessage.comment = result.evaluationComment
  // 刚发出的题目若为追问，气泡打上标识
  assistantMessage.followUp = !!result.status?.currentQuestionFollowUp
  status.value = result.status
  if (result.action === 'FINISH' || result.status?.state === 'FINISHED') {
    await finishAndShowReport()
  }
}

function requestFinish() {
  confirmFinish.value = true
}

function cancelFinish() {
  confirmFinish.value = false
}

async function finishInterview() {
  confirmFinish.value = false
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
    notifyError(e, finishAndShowReport)
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
      <div class="resume-row">
        <template v-if="resumes.length > 1">
          <label class="muted" for="resume-select">选择本次面试使用的简历：</label>
          <select id="resume-select" v-model="selectedResumeId" :disabled="sending">
            <option :value="null">不使用简历（通用项目题）</option>
            <option v-for="item in resumes" :key="item.id" :value="item.id">
              {{ item.name || '未命名候选人' }}
            </option>
          </select>
        </template>
        <template v-else-if="resumes.length === 1">
          <span class="muted">已自动关联简历：</span>
          <span class="badge">{{ resumes[0].name || '未命名候选人' }}</span>
        </template>
        <template v-else>
          <span class="muted">暂无简历，面试将使用通用项目题；可先去 <RouterLink to="/resume">简历管理</RouterLink> 创建</span>
        </template>
      </div>
      <p v-if="error" class="error-text">{{ error }}</p>
    </div>

    <!-- 进行中 -->
    <template v-else>
      <!-- 顶部进度条：当前阶段 / 总阶段 -->
      <div class="stage-progress-wrap">
        <div class="stage-progress-track">
          <div class="stage-progress-fill" :style="{ width: stagePercent + '%' }"></div>
        </div>
        <span class="muted stage-progress-text">
          阶段 {{ stageIndex + 1 }} / {{ STAGES.length }} · {{ status?.phaseLabel || '—' }}
        </span>
      </div>

      <div class="interview-layout">
        <div class="interview-main">
          <!-- 平板档：状态折叠在顶部；桌面档隐藏（信息移到右侧边栏），手机档隐藏（收入底部抽屉） -->
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
            <button v-if="!isFinished" class="ghost finish-btn" :disabled="phase === 'finishing'" @click="requestFinish">
              结束面试
            </button>
          </div>

      <div ref="chatBox" class="card chat-box">
        <div v-for="(item, index) in messages" :key="index" :class="['bubble-row', item.role, { followup: item.followUp }]">
          <div class="bubble">
            <div v-if="item.followUp" class="followup-tag"><span class="badge warning">🔄 追问</span></div>
            <div class="bubble-content">
              {{ item.content }}<span v-if="item.restored" class="muted">（刷新恢复，历史消息不回放）</span>
              <span v-if="isStreamingItem(item)" class="stream-cursor"></span>
            </div>
            <div v-if="item.score != null" class="score-line">
              <span :class="['badge', scoreClass(item.score)]">得分 {{ item.score }}</span>
              <span v-if="item.comment" class="muted comment">{{ item.comment }}</span>
            </div>
          </div>
        </div>
        <div v-if="thinking" class="bubble-row assistant">
          <div class="bubble typing-bubble" aria-label="面试官思考中">
            <span class="typing-dot"></span><span class="typing-dot"></span><span class="typing-dot"></span>
          </div>
        </div>
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
        <button
          type="button"
          class="ghost skip-btn"
          title="跳过当前题（本题计 0 分）"
          :disabled="sending || !canSkip"
          @click="skipQuestion"
        >
          跳过此题
        </button>
      </form>
        </div>

        <!-- 桌面档：右侧边栏展示面试状态 -->
        <aside class="card side-panel">
          <h3>面试状态</h3>
          <div class="side-row">
            <span class="muted">当前环节</span>
            <span class="badge">{{ status?.phaseLabel || '—' }}</span>
          </div>
          <div class="side-row">
            <span class="muted">难度</span>
            <span :class="['badge', difficultyClass]">{{ status?.difficultyLabel || '—' }}</span>
          </div>
          <div class="side-row">
            <span class="muted">追问进度</span>
            <span class="badge warning">{{ status?.followUpsUsed ?? 0 }}/{{ status?.followUpLimit ?? 0 }}</span>
          </div>
          <div class="side-row">
            <span class="muted">题目进度</span>
            <span>{{ progressText || '—' }} 题</span>
          </div>
          <div class="side-row">
            <span class="muted">平均分</span>
            <span>{{ status?.averageScore ? status.averageScore.toFixed(1) : '—' }}</span>
          </div>
          <button
            v-if="!isFinished"
            class="secondary finish-btn-side"
            :disabled="phase === 'finishing'"
            @click="requestFinish"
          >
            结束面试
          </button>
        </aside>
      </div>

      <!-- 手机档：状态抽屉入口 -->
      <button class="status-fab" @click="statusSheetOpen = true">📊 状态</button>

      <!-- 手机档：底部抽屉（Bottom Sheet） -->
      <Transition name="sheet">
        <div v-if="statusSheetOpen" class="sheet-mask" @click="statusSheetOpen = false">
          <div class="bottom-sheet card" @click.stop>
            <div class="sheet-handle"></div>
            <h3>面试状态</h3>
            <div class="side-row">
              <span class="muted">当前环节</span>
              <span class="badge">{{ status?.phaseLabel || '—' }}</span>
            </div>
            <div class="side-row">
              <span class="muted">难度</span>
              <span :class="['badge', difficultyClass]">{{ status?.difficultyLabel || '—' }}</span>
            </div>
            <div class="side-row">
              <span class="muted">追问进度</span>
              <span class="badge warning">{{ status?.followUpsUsed ?? 0 }}/{{ status?.followUpLimit ?? 0 }}</span>
            </div>
            <div class="side-row">
              <span class="muted">题目进度</span>
              <span>{{ progressText || '—' }} 题</span>
            </div>
            <div class="side-row">
              <span class="muted">平均分</span>
              <span>{{ status?.averageScore ? status.averageScore.toFixed(1) : '—' }}</span>
            </div>
            <button
              v-if="!isFinished"
              class="finish-btn-side"
              :disabled="phase === 'finishing'"
              @click="statusSheetOpen = false; requestFinish()"
            >
              结束面试
            </button>
          </div>
        </div>
      </Transition>

      <!-- 结束面试：报告生成进度遮罩 -->
      <div v-if="phase === 'finishing'" class="loading-overlay">
        <div class="card loading-progress">
          <div class="spinner"></div>
          <p>正在生成反馈报告...</p>
          <p class="muted">AI 正在汇总各题评分与能力维度，请稍候</p>
        </div>
      </div>

      <!-- 结束面试二次确认 -->
      <div v-if="confirmFinish" class="loading-overlay">
        <div class="card confirm-dialog">
          <h3>确定提前结束面试？</h3>
          <p class="muted">未作答的剩余题目将被跳过，报告将基于已作答题目生成（跳过的题计 0 分）。</p>
          <div class="confirm-actions">
            <button class="secondary" @click="cancelFinish">继续面试</button>
            <button @click="finishInterview">确认结束</button>
          </div>
        </div>
      </div>

      <!-- 阶段过渡横幅：淡入淡出 -->
      <Transition name="phase-fade">
        <div v-if="phaseBanner" class="phase-banner">进入：{{ phaseBanner }}</div>
      </Transition>
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

.resume-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
}

.resume-row select {
  width: auto;
  min-width: 220px;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 14px;
  background: #fff;
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
  animation: bubble-in 0.3s ease;
}

@keyframes bubble-in {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.bubble-row.followup .bubble {
  background: #fff7e6;
  border: 1px solid #ffe3ad;
}

.followup-tag {
  margin-bottom: 6px;
}

.stream-cursor {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 2px;
  background: var(--text);
  vertical-align: -2px;
  animation: cursor-blink 1s steps(1) infinite;
}

@keyframes cursor-blink {
  50% {
    opacity: 0;
  }
}

.stage-progress-wrap {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.stage-progress-track {
  flex: 1;
  height: 8px;
  background: #eef1f6;
  border-radius: 4px;
  overflow: hidden;
}

.stage-progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #6c8cff, #4f6ef7);
  border-radius: 4px;
  transition: width 0.6s;
}

.stage-progress-text {
  font-size: 12px;
  white-space: nowrap;
}

.skip-btn {
  flex-shrink: 0;
}

.skip-btn:disabled {
  background: transparent;
  color: #c3c9d4;
}

.skip-btn:hover:not(:disabled) {
  color: var(--warning);
}

.confirm-dialog {
  width: 380px;
  max-width: 90vw;
  text-align: center;
  padding: 28px;
}

.confirm-dialog h3 {
  margin-bottom: 10px;
}

.confirm-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
  margin-top: 20px;
}

.phase-banner {
  position: fixed;
  top: 40%;
  left: 50%;
  transform: translate(-50%, -50%);
  padding: 16px 36px;
  background: rgba(31, 41, 55, 0.85);
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  border-radius: 12px;
  z-index: 90;
  pointer-events: none;
}

.phase-fade-enter-active,
.phase-fade-leave-active {
  transition: opacity 0.5s ease;
}

.phase-fade-enter-from,
.phase-fade-leave-to {
  opacity: 0;
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

/* ---------- 响应式三档 ---------- */
.interview-layout {
  display: block;
}

.interview-main {
  min-width: 0;
}

.side-panel {
  display: none;
}

.status-fab {
  display: none;
}

.side-panel h3,
.bottom-sheet h3 {
  font-size: 15px;
  margin-bottom: 12px;
}

.side-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px dashed var(--border);
  font-size: 13px;
}

.side-row:last-of-type {
  border-bottom: none;
}

.finish-btn-side {
  width: 100%;
  margin-top: 14px;
}

/* 桌面档（1200px+）：对话区 + 右侧边栏 */
@media (min-width: 1200px) {
  .interview-layout {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 280px;
    gap: 16px;
    align-items: start;
  }

  .status-bar {
    display: none;
  }

  .side-panel {
    display: block;
    position: sticky;
    top: 84px;
  }
}

/* 手机档（<768px）：状态收入底部抽屉 */
@media (max-width: 767px) {
  .status-bar {
    display: none;
  }

  .chat-box {
    height: 48vh;
  }

  .answer-row {
    flex-wrap: wrap;
  }

  .answer-row textarea {
    flex-basis: 100%;
  }

  .status-fab {
    display: flex;
    position: fixed;
    right: 16px;
    bottom: 20px;
    z-index: 50;
    align-items: center;
    gap: 6px;
    box-shadow: 0 6px 18px rgba(31, 41, 55, 0.2);
  }
}

.sheet-mask {
  position: fixed;
  inset: 0;
  background: rgba(31, 41, 55, 0.35);
  z-index: 80;
  display: flex;
  align-items: flex-end;
}

.bottom-sheet {
  width: 100%;
  border-radius: 16px 16px 0 0;
  padding: 16px 20px 24px;
  max-height: 70vh;
  overflow-y: auto;
}

.sheet-handle {
  width: 40px;
  height: 4px;
  border-radius: 2px;
  background: #d5dae5;
  margin: 0 auto 14px;
}

.sheet-enter-active,
.sheet-leave-active {
  transition: opacity 0.25s ease;
}

.sheet-enter-active .bottom-sheet,
.sheet-leave-active .bottom-sheet {
  transition: transform 0.25s ease;
}

.sheet-enter-from,
.sheet-leave-to {
  opacity: 0;
}

.sheet-enter-from .bottom-sheet,
.sheet-leave-to .bottom-sheet {
  transform: translateY(100%);
}
</style>
