<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { marked } from 'marked'
import {
  knowledgeApi,
  trainingApi,
  quotaApi
} from '../api'
import { classifyError } from '../utils/errors'
import { isMobileViewport } from '../utils/device'
import { toast } from '../toast'
import {
  trainingSession,
  startTrainingSession,
  submitTrainingAnswer,
  restoreTrainingSession,
  clearTrainingSession,
  TRAINING_SESSION_KEY
} from '../store/trainingSession'

// 教练/导师话术含 Markdown（粗体/斜体/列表等），与面试页同款渲染配置；html 默认关闭，原始 HTML 会被转义
marked.use({ gfm: true, breaks: true })

function renderMarkdown(content) {
  if (!content) {
    return ''
  }
  return marked.parse(content)
}

const route = useRoute()
const router = useRouter()
// 手机端不展示键盘快捷键提示，placeholder 简化
const answerPlaceholder = isMobileViewport()
  ? '输入你的回答…'
  : '输入你的回答…（Enter 发送，Shift+Enter 换行）'
// 任务 4：由模拟面试「深入该模块」跳转而来，训练结束后引导回面试页续考
const fromInterview = route.query.from === 'interview'

// 存在会话指针时先进恢复中占位，避免刷新后先闪选择页再跳回对话
function hasSessionMarker() {
  if (trainingSession.sessionId) {
    return true
  }
  try {
    return Boolean(sessionStorage.getItem(TRAINING_SESSION_KEY))
  } catch {
    return false
  }
}

const phase = ref(hasSessionMarker() ? 'restoring' : 'select') // restoring | select | active | summary
const categoryOptions = ref([])
const records = ref([])
const quotaInfo = ref(null)
const localError = ref('')

// 会话状态全部来自全局 store：切标签/流式回合进行中都不丢失
const status = computed(() => trainingSession.status)
const messages = computed(() => trainingSession.messages)
const thinkingText = computed(() => trainingSession.thinkingText)
const starting = ref(false)
const sending = computed(() => trainingSession.sending || starting.value)
const error = computed(() => localError.value || trainingSession.error)

const answer = ref('')
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
  // 恢复会话：切标签回来直接沿用模块级消息；刷新页面按后端历史完整重建对话
  const restored = await restoreTrainingSession()
  if (restored) {
    phase.value = 'active'
    scrollDown()
    return
  }
  // 无可恢复会话：回到选题页
  phase.value = 'select'
  // 任务 4：带目标分组跳转而来（面试「深入该模块」）：自动开启该分组专项训练
  const targetCategory = route.query.category
  if (typeof targetCategory === 'string' && targetCategory.trim()) {
    startTraining(targetCategory.trim())
  }
})

// 流式内容/新消息/阶段提示变化时滚到底部，保证最新内容可见
watch(
  () => [trainingSession.messages.length, trainingSession.messages.at(-1)?.content, trainingSession.thinkingText],
  () => {
    if (phase.value === 'active') {
      scrollDown()
    }
  }
)

// 回合结束（达到题数/题库耗尽）：store 更新 status.finished 后转入成绩页
watch(
  () => trainingSession.status?.finished,
  (finished) => {
    if (finished && phase.value === 'active') {
      endSession()
    }
  }
)

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
    records.value = (await trainingApi.records(0, 5)).content || []
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
  localError.value = ''
  starting.value = true
  try {
    await startTrainingSession(category, assistantStyle.value, fromInterview)
    phase.value = 'active'
    refreshQuota()
    scrollDown()
  } catch (e) {
    if (e.code === 'QUOTA_EXCEEDED') {
      localError.value = '今日免费额度已用完，可前往「设置」配置自己的 API Key 继续使用'
      toast.error(e.message || '今日免费额度已用完')
      refreshQuota()
    } else {
      localError.value = classifyError(e).message
    }
  } finally {
    starting.value = false
  }
}

// 提交作答：流式回合挂在全局 store，切标签后台继续，评价与回复不丢失
function submitAnswer() {
  const text = answer.value.trim()
  if (!text || sending.value) {
    return
  }
  answer.value = ''
  submitTrainingAnswer(text)
}

function onEnterSend(event) {
  // Enter 发送，Shift+Enter 换行
  if (event.shiftKey) {
    return
  }
  event.preventDefault()
  submitAnswer()
}

function endSession() {
  phase.value = 'summary'
  loadRecords()
}

// 主动结束训练：归档已作答成绩后展示成绩卡
async function quitTraining() {
  if (sending.value || !trainingSession.sessionId) {
    return
  }
  starting.value = true
  try {
    const result = await trainingApi.finish(trainingSession.sessionId)
    trainingSession.status = result
    endSession()
  } catch (e) {
    localError.value = classifyError(e).message
  } finally {
    starting.value = false
  }
}

function backToSelect() {
  clearTrainingSession()
  phase.value = 'select'
  selectedCategory.value = ''
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
        <div class="recent-records-head">
          <h3>最近训练</h3>
          <button class="link-btn" @click="router.push('/history/trainings')">查看全部 →</button>
        </div>
        <div v-for="record in records" :key="record.id" class="record-row">
          <span>{{ record.category }}</span>
          <span class="muted">{{ record.askedCount }} 题 · 最高难度 {{ difficultyLabel(record.maxDifficulty) }}</span>
          <span :class="['badge', scoreClass(record.averageScore / 10)]">{{ record.averageScore.toFixed(1) }} 分</span>
          <button class="link-btn" @click="router.push(`/training-report/${record.id}`)">查看报告</button>
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
            <div class="bubble-content">
              <!-- eslint-disable-next-line vue/no-v-html -->
              <div v-if="item.role === 'assistant'" class="md" v-html="renderMarkdown(item.content)"></div>
              <template v-else>{{ item.content }}</template>
              <span v-if="item.restored" class="muted">（刷新恢复）</span>
            </div>
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
                    <!-- eslint-disable-next-line vue/no-v-html -->
                    <blockquote class="md" v-html="renderMarkdown(item.evaluation.improvedAnswer)"></blockquote>
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
          :placeholder="answerPlaceholder"
          :disabled="sending || status?.finished"
          @keydown.enter="onEnterSend"
        ></textarea>
        <button :disabled="sending || !answer.trim() || status?.finished" @click="submitAnswer">
          {{ sending ? '评估中…' : '提交回答' }}
        </button>
      </div>
      <p v-if="error" class="error-text">{{ error }}</p>
    </template>

    <!-- 完成成绩 -->
    <div v-else-if="phase === 'summary'" class="card summary-card">
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

    <!-- 恢复中占位：刷新/进入页面时先查后端会话，避免闪回选题页 -->
    <div v-else class="card restoring-card">
      <p class="muted">正在恢复训练会话…</p>
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

/* 恢复中占位：居中轻提示，不抢视觉 */
.restoring-card {
  padding: 48px 20px;
  text-align: center;
}

/* 气泡内 Markdown 渲染（与面试页同配置）：v-html 内容需穿透 scoped 样式 */
.md :deep(p) {
  margin: 0 0 8px;
}

.md :deep(p:last-child) {
  margin-bottom: 0;
}

.md :deep(strong) {
  font-weight: 600;
}

.md :deep(ul),
.md :deep(ol) {
  margin: 4px 0 8px;
  padding-left: 20px;
}

.md :deep(li) {
  margin-bottom: 2px;
}

.md :deep(code) {
  background: #e5eaf6;
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 13px;
}

.md :deep(pre) {
  background: #eef1f8;
  padding: 8px 12px;
  border-radius: 8px;
  overflow-x: auto;
  margin: 6px 0;
}

.md :deep(pre code) {
  background: transparent;
  padding: 0;
}

.md :deep(blockquote) {
  border-left: 3px solid #c8d2f5;
  padding-left: 10px;
  color: var(--text-light);
  margin: 6px 0;
}

.md :deep(h1),
.md :deep(h2),
.md :deep(h3) {
  font-size: 15px;
  font-weight: 600;
  margin: 8px 0 6px;
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
  color: var(--primary, #4f6ef7);
}

.category-tag {
  font-size: 12px;
}

.recent-records {
  margin-top: 22px;
  border-top: 1px solid var(--border, #e3e6ef);
  padding-top: 14px;
}

.recent-records-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.recent-records-head h3 {
  margin-bottom: 8px;
}

.link-btn {
  background: none;
  border: none;
  padding: 0;
  color: var(--primary, #4f6ef7);
  font-size: 13px;
  cursor: pointer;
}

.link-btn:hover {
  text-decoration: underline;
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
