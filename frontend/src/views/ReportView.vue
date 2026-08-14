<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import * as echarts from 'echarts'
import { reportApi } from '../api'
import { classifyError } from '../utils/errors'

const route = useRoute()
const report = ref(null)
const loading = ref(true)
const error = ref('')
const expanded = ref({})

const radarEl = ref(null)
let radarChart = null

const ratingClass = computed(() => {
  const rating = report.value?.rating
  if (rating === '优秀') return 'success'
  if (rating === '良好') return ''
  if (rating === '及格') return 'warning'
  return 'danger'
})

const phaseBars = computed(() => {
  if (!report.value) {
    return []
  }
  return [
    { label: '基础题', score: report.value.basicsScore },
    { label: '项目题', score: report.value.projectScore },
    { label: '深挖题', score: report.value.deepScore }
  ]
})

function formatTime(value) {
  return value ? new Date(value).toLocaleString('zh-CN') : '—'
}

function toggle(index) {
  expanded.value[index] = !expanded.value[index]
}

// 导出 PDF：展开全部逐题点评后走浏览器打印
function exportPdf() {
  for (const item of report.value?.questionEvaluations || []) {
    expanded.value[item.questionIndex] = true
  }
  nextTick(() => window.print())
}

function renderRadar() {
  if (!radarEl.value || !report.value) {
    return
  }
  if (!radarChart) {
    radarChart = echarts.init(radarEl.value)
  }
  const r = report.value
  radarChart.setOption({
    radar: {
      indicator: [
        { name: '准确性', max: 10 },
        { name: '完整性', max: 10 },
        { name: '清晰度', max: 10 },
        { name: '深度', max: 10 },
        { name: '总分', max: 10 }
      ],
      radius: '68%'
    },
    series: [
      {
        type: 'radar',
        data: [
          {
            value: [
              r.avgAccuracy,
              r.avgCompleteness,
              r.avgClarity,
              r.avgDepth,
              Math.round((r.overallScore / 10) * 10) / 10
            ],
            name: '能力维度',
            areaStyle: { opacity: 0.25 }
          }
        ],
        itemStyle: { color: '#4f6ef7' }
      }
    ]
  })
}

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    report.value = await reportApi.get(route.params.interviewId)
    await nextTick()
    renderRadar()
  } catch (e) {
    error.value = classifyError(e).message
  } finally {
    loading.value = false
  }
}

watch(report, async () => {
  await nextTick()
  renderRadar()
})

onBeforeUnmount(() => {
  radarChart?.dispose()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h1 class="page-title">面试报告</h1>
      <button v-if="report" class="secondary print-btn" @click="exportPdf">🖨 导出 PDF</button>
    </div>
    <template v-if="loading">
      <div class="card section">
        <div class="skeleton skeleton-title"></div>
        <div class="skeleton skeleton-chart"></div>
      </div>
      <div class="card section">
        <div class="skeleton skeleton-title"></div>
        <div v-for="i in 4" :key="i" class="skeleton skeleton-text"></div>
      </div>
    </template>

    <div v-else-if="error" class="card section error-alert">
      <p class="error-text">{{ error }}</p>
      <button class="secondary" @click="load">重试</button>
    </div>

    <template v-else-if="report">
      <!-- 顶部综合评分 -->
      <div class="card overview">
        <div class="score-block">
          <div class="big-score">{{ report.overallScore.toFixed(1) }}</div>
          <div class="muted">综合评分（满分 100）</div>
          <span :class="['badge', ratingClass]">{{ report.rating }}</span>
        </div>
        <div class="meta">
          <p><span class="muted">岗位方向：</span>{{ report.position }}</p>
          <p><span class="muted">面试时间：</span>{{ formatTime(report.interviewTime) }}</p>
          <p>
            <span class="muted">题量：</span>{{ report.totalQuestions }} 题 · 追问 {{ report.totalFollowUps }} 次 ·
            时长 {{ report.durationMinutes }} 分钟
          </p>
        </div>
        <div ref="radarEl" class="radar"></div>
      </div>

      <!-- 各阶段表现 -->
      <div class="card section">
        <h2>各阶段表现</h2>
        <div v-for="bar in phaseBars" :key="bar.label" class="phase-bar-row">
          <span class="phase-label">{{ bar.label }}</span>
          <div class="bar-track">
            <div class="bar-fill" :style="{ width: bar.score * 10 + '%' }"></div>
          </div>
          <span class="phase-score">{{ bar.score.toFixed(1) }}</span>
        </div>
      </div>

      <!-- 亮点与薄弱点 -->
      <div class="two-col">
        <div class="card section">
          <h2>✨ 亮点</h2>
          <ul class="point-list">
            <li v-for="(item, index) in report.strengths" :key="index">{{ item }}</li>
            <li v-if="!report.strengths.length" class="muted">暂无</li>
          </ul>
        </div>
        <div class="card section">
          <h2>⚠️ 薄弱点</h2>
          <ul class="point-list">
            <li v-for="(item, index) in report.weaknesses" :key="index">{{ item }}</li>
            <li v-if="!report.weaknesses.length" class="muted">暂无</li>
          </ul>
        </div>
      </div>

      <!-- 改进建议 -->
      <div class="card section">
        <h2>改进建议</h2>
        <div class="suggestion-grid">
          <div v-for="(item, index) in report.suggestions" :key="index" class="suggestion-card">
            <div class="suggestion-index">{{ index + 1 }}</div>
            <div>{{ item }}</div>
          </div>
          <p v-if="!report.suggestions.length" class="muted">暂无</p>
        </div>
      </div>

      <!-- 推荐复习材料 -->
      <div class="card section">
        <h2>推荐复习材料</h2>
        <div v-for="(item, index) in report.recommendedMaterials" :key="index" class="material-item">
          <div class="material-topic">📚 {{ item.topic }}</div>
          <p class="muted">{{ item.reason }}</p>
          <p>建议练习：<strong>{{ item.suggestedQuestion }}</strong></p>
        </div>
        <p v-if="!report.recommendedMaterials.length" class="muted">整体表现稳定，暂无重点复习项</p>
      </div>

      <!-- 逐题点评 -->
      <div class="card section">
        <h2>逐题点评</h2>
        <div v-for="item in report.questionEvaluations" :key="item.questionIndex" class="question-item">
          <div class="question-header" @click="toggle(item.questionIndex)">
            <span class="question-index">Q{{ item.questionIndex }}</span>
            <span v-if="item.followUp" class="badge warning">🔄 追问</span>
            <span class="question-text">{{ item.question }}</span>
            <span :class="['badge', item.score >= 7 ? 'success' : item.score >= 4 ? 'warning' : 'danger']">
              {{ item.score.toFixed(1) }} 分
            </span>
            <span class="expand-icon">{{ expanded[item.questionIndex] ? '▾' : '▸' }}</span>
          </div>
          <div v-if="expanded[item.questionIndex]" class="question-detail">
            <p><span class="muted">你的回答：</span>{{ item.userAnswer }}</p>
            <p><span class="muted">点评：</span>{{ item.feedback }}</p>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.page-head .page-title {
  margin-bottom: 0;
}

.overview {
  display: grid;
  grid-template-columns: 200px 1fr 340px;
  gap: 20px;
  align-items: center;
  margin-bottom: 16px;
}

.big-score {
  font-size: 48px;
  font-weight: 800;
  color: var(--primary);
}

.meta p {
  margin-bottom: 6px;
}

.radar {
  height: 260px;
}

.section {
  margin-bottom: 16px;
}

.section h2 {
  font-size: 16px;
  margin-bottom: 14px;
}

.phase-bar-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.phase-label {
  width: 56px;
}

.bar-track {
  flex: 1;
  height: 12px;
  background: #eef1f6;
  border-radius: 6px;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  background: linear-gradient(90deg, #6c8cff, #4f6ef7);
  border-radius: 6px;
  transition: width 0.6s;
}

.phase-score {
  width: 40px;
  text-align: right;
  font-weight: 600;
}

.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 16px;
}

.point-list {
  padding-left: 18px;
}

.point-list li {
  margin-bottom: 6px;
}

.suggestion-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}

.suggestion-card {
  display: flex;
  gap: 10px;
  background: #f6f8fd;
  border-radius: 8px;
  padding: 12px;
}

.suggestion-index {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--primary);
  color: #fff;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.material-item {
  padding: 10px 0;
  border-bottom: 1px dashed var(--border);
}

.material-item:last-child {
  border-bottom: none;
}

.material-topic {
  font-weight: 600;
}

.question-item {
  border-bottom: 1px solid var(--border);
}

.question-item:last-child {
  border-bottom: none;
}

.question-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 4px;
  cursor: pointer;
}

.question-index {
  font-weight: 700;
  color: var(--primary);
}

.question-text {
  flex: 1;
}

.expand-icon {
  color: var(--text-light);
}

.question-detail {
  padding: 0 4px 14px;
}

.question-detail p {
  margin-bottom: 6px;
}

@media (max-width: 860px) {
  .overview {
    grid-template-columns: 1fr;
  }

  .two-col {
    grid-template-columns: 1fr;
  }
}

/* 手机档：雷达图缩小，逐题点评全宽卡片（题目文本独占一行） */
@media (max-width: 767px) {
  .radar {
    height: 200px;
  }

  .big-score {
    font-size: 40px;
  }

  .question-header {
    flex-wrap: wrap;
    row-gap: 6px;
  }

  .question-text {
    flex-basis: 100%;
    order: 5;
  }

  .suggestion-grid {
    grid-template-columns: 1fr;
  }
}
</style>
