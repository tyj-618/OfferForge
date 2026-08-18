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

// 助手语气风格：strict 严肃专业 / friendly 和蔼可亲（缺省；query 自动开局也用缺省值）
const assistantStyle = ref('friendly')
const STYLE_OPTIONS = [
  { value: 'friendly', label: '😊 和蔼可亲', desc: '温和鼓励，高信息浓度' },
  { value: 'strict', label: '🧊 严肃专业', desc: '效率优先，专注知识内容' }
]

// 分组两级选择：一级来源标签（官方题库/我的资料）→ 二级具体分组 → 确认后才开局
const sourceTab = ref('official') // official | custom
const selectedCategory = ref('')
const officialCategories = computed(() => categoryOptions.value.filter((opt) => opt.official))
const customCategories = computed(() => categoryOptions.value.filter((opt) => !opt.official))
const visibleCategories = computed(() => (sourceTab.value === 'official' ? officialCategories.value : customCategories.value))

function switchSourceTab(tab) {
  if (sending.value || sourceTab.value === tab) {
    return
  }
  sourceTab.value = tab
  selectedCategory.value = ''
}

function selectCategory(name) {
  if (sending.value) {
    return
  }
  selectedCategory.value = selectedCategory.value === name ? '' : name
}

function confirmStart() {
  if (!selectedCategory.value || sending.value) {
    return
  }
  startTraining(selectedCategory.value)
}

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
    // 默认落在有内容的来源标签上，避免开局即看到空列表
    if (!view?.official?.length && view?.custom?.length) {
      sourceTab.value = 'custom'
    }
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
    const data = await trainingApi.start(category, assistantStyle.value)
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
      <!-- 助手语气风格：贯穿教练出题与导师点评话术 -->
      <div class="style-row">
        <span class="muted">助手风格：</span>
        <label v-for="opt in STYLE_OPTIONS" :key="opt.value" class="style-chip" :class="{ active: assistantStyle === opt.value }">
          <input v-model="assistantStyle" type="radio" name="training-style" :value="opt.value" :disabled="sending" />
          <span class="style-name">{{ opt.label }}</span>
          <span class="muted style-desc">{{ opt.desc }}</span>
        </label>
      </div>
      <!-- 一级标签：题库来源（官方题库 / 我的资料） -->
      <div v-if="categoryOptions.length" class="source-tabs">
        <button
          v-for="tab in [{ key: 'official', label: '官方题库', count: officialCategories.length }, { key: 'custom', label: '我的资料', count: customCategories.length }]"
          :key="tab.key"
          type="button"
          class="source-tab"
          :class="{ active: sourceTab === tab.key }"
          :disabled="sending"
          @click="switchSourceTab(tab.key)"
        >
          {{ tab.label }}
          <span class="source-count">{{ tab.count }}</span>
        </button>
      </div>
      <!-- 二级标签：具体分组，选中后需确认才开局 -->
      <div v-if="visibleCategories.length" class="category-grid">
        <button
          v-for="opt in visibleCategories"
          :key="opt.name"
          type="button"
          class="category-card"
          :class="{ selected: selectedCategory === opt.name }"
          :disabled="sending"
          @click="selectCategory(opt.name)"
        >
          <span class="category-name">{{ opt.name }}</span>
          <span class="muted category-tag">{{ opt.official ? '官方题库' : '我的资料' }}</span>
        </button>
      </div>
      <p v-else-if="categoryOptions.length" class="muted empty-category">该来源下暂无可用分组，可前往 <RouterLink to="/library">资源库</RouterLink> 导入或上传资料。</p>
      <p v-else class="muted">暂无可用分组，请先前往 <RouterLink to="/library">资源库</RouterLink> 导入官方题库或上传资料。</p>

      <!-- 开局确认：选定分组后二次确认，避免误触直接开局 -->
      <div v-if="selectedCategory" class="confirm-panel">
        <p>
          即将开始 <strong>「{{ selectedCategory }}」</strong>
          <span class="muted">（{{ sourceTab === 'official' ? '官方题库' : '我的资料' }}）</span>
          的专项训练，开局后由浅入深出题并消耗 1 次额度（自带 API Key 不消耗）。确认开始吗？
        </p>
        <div class="confirm-actions">
          <button type="button" class="ghost" :disabled="sending" @click="selectedCategory = ''">重新选择</button>
          <button type="button" :disabled="sending" @click="confirmStart">{{ sending ? '正在开启…' : '确认开始' }}</button>
        </div>
      </div>
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

/* 助手风格选择：胶囊单选，选中态高亮 */
.style-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 12px;
  font-size: 14px;
}

.style-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid var(--border, #d9deeb);
  border-radius: 999px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}

.style-chip input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}

.style-chip.active {
  border-color: var(--primary);
  background: rgba(79, 110, 247, 0.08);
}

.style-name {
  font-weight: 600;
}

.style-desc {
  font-size: 12px;
}

.category-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 12px;
  margin-top: 14px;
}

/* 一级来源标签：分段式按钮，选中态高亮 */
.source-tabs {
  display: inline-flex;
  gap: 4px;
  margin-top: 14px;
  padding: 4px;
  border: 1px solid var(--border, #e3e6ef);
  border-radius: 10px;
  background: var(--bg-subtle, #f5f6fa);
}

.source-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 16px;
  border: none;
  border-radius: 8px;
  background: transparent;
  font-weight: 500;
  color: var(--text-muted, #6b7280);
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.source-tab.active {
  background: var(--bg-card, #fff);
  color: var(--primary, #4f6ef7);
  font-weight: 600;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}

.source-count {
  font-size: 12px;
  padding: 0 6px;
  border-radius: 999px;
  background: rgba(79, 110, 247, 0.12);
  color: var(--primary, #4f6ef7);
}

.empty-category {
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
  background: #f5f6fa;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s, background 0.15s;
}

.category-card:hover:not(:disabled) {
  border-color: var(--primary, #4f6ef7);
  box-shadow: 0 2px 8px rgba(79, 110, 247, 0.15);
}

/* 二级分组选中态：等待确认开局 */
.category-card.selected {
  border-color: var(--primary, #4f6ef7);
  background: rgba(79, 110, 247, 0.08);
  box-shadow: 0 2px 8px rgba(79, 110, 247, 0.18);
}

/* 开局确认面板 */
.confirm-panel {
  margin-top: 16px;
  padding: 14px 16px;
  border: 1px solid var(--border, #e3e6ef);
  border-left: 3px solid var(--primary, #4f6ef7);
  border-radius: 10px;
  background: #f7f8fc;
}

.confirm-panel p {
  margin: 0 0 12px;
  line-height: 1.6;
}

.confirm-actions {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
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
