<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { reportApi, trainingApi } from '../api'
import { classifyError } from '../utils/errors'

const router = useRouter()
const loading = ref(true)
const error = ref('')
// 面试记录按模式划分：出现在历史里的均为已完成的场次，不再展示状态列
const trainingItems = ref([])
const trainingTotal = ref(0)
const practiceItems = ref([])
const practiceTotal = ref(0)
// 专项训练记录：与面试记录同样仅保留最近 5 条概要
const trainingRecords = ref([])
const trainingRecordsTotal = ref(0)
const progressPoints = ref([])

const trendEl = ref(null)
let trendChart = null

function formatTime(value) {
  return value ? new Date(value).toLocaleString('zh-CN') : '—'
}

function ratingOf(score) {
  if (score >= 85) return '优秀'
  if (score >= 70) return '良好'
  if (score >= 60) return '及格'
  return '需努力'
}

function scoreClass(score) {
  if (score >= 85) return 'success'
  if (score >= 60) return 'warning'
  return 'danger'
}

function difficultyLabel(value) {
  if (value === 'HARD') return '困难'
  if (value === 'MEDIUM') return '中等'
  return '简单'
}

function renderTrend() {
  // 单点无法形成趋势线，由模板展示友好提示，不初始化图表
  if (!trendEl.value || progressPoints.value.length < 2) {
    return
  }
  if (!trendChart) {
    trendChart = echarts.init(trendEl.value)
  }
  trendChart.setOption({
    grid: { left: 44, right: 20, top: 28, bottom: 30 },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: progressPoints.value.map((point, index) => `第 ${index + 1} 次`),
      axisLabel: { color: '#6b7280' }
    },
    yAxis: { type: 'value', min: 0, max: 100, axisLabel: { color: '#6b7280' } },
    series: [
      {
        name: '综合评分',
        type: 'line',
        smooth: true,
        data: progressPoints.value.map((point) => point.overallScore),
        itemStyle: { color: '#4f6ef7' },
        areaStyle: { opacity: 0.12 }
      }
    ]
  })
}

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [trainingHistory, practiceHistory, records, progress] = await Promise.all([
      reportApi.history(0, 5, 'training'),
      reportApi.history(0, 5, 'practice'),
      trainingApi.records(0, 5),
      reportApi.progress(10)
    ])
    trainingItems.value = trainingHistory.content || []
    trainingTotal.value = trainingHistory.totalElements || 0
    practiceItems.value = practiceHistory.content || []
    practiceTotal.value = practiceHistory.totalElements || 0
    trainingRecords.value = records.content || []
    trainingRecordsTotal.value = records.totalElements || 0
    progressPoints.value = progress || []
    await nextTick()
    renderTrend()
  } catch (e) {
    error.value = classifyError(e).message
  } finally {
    loading.value = false
  }
}

onBeforeUnmount(() => {
  trendChart?.dispose()
})
</script>

<template>
  <div class="page">
    <h1 class="page-title">历史报告</h1>
    <template v-if="loading">
      <div class="card section">
        <div class="skeleton skeleton-title"></div>
        <div class="skeleton skeleton-chart"></div>
      </div>
      <div class="card section">
        <div class="skeleton skeleton-title"></div>
        <div v-for="i in 4" :key="i" class="skeleton skeleton-row"></div>
      </div>
    </template>

    <div v-else-if="error" class="card section error-alert">
      <p class="error-text">{{ error }}</p>
      <button class="secondary" @click="load">重试</button>
    </div>

    <template v-else>
      <div class="card section">
        <h2>评分趋势（最近 10 次）</h2>
        <div v-if="progressPoints.length >= 2" ref="trendEl" class="trend"></div>
        <p v-else-if="progressPoints.length === 1" class="empty">
          已完成 1 次面试，再完成至少 1 次即可展示评分趋势
        </p>
        <p v-else class="empty">暂无面试记录，先去「模拟面试」完成一场吧</p>
      </div>

      <div class="card section">
        <div class="section-head">
          <h2>面试记录 · 训练模式（最近 5 条）</h2>
          <button v-if="trainingTotal > 5" class="link-btn" @click="router.push('/history/interviews?tab=training')">
            查看全部（{{ trainingTotal }} 次） →
          </button>
        </div>
        <div v-if="trainingItems.length" class="table-wrap">
          <table class="history-table">
          <thead>
            <tr>
              <th>面试时间</th>
              <th>岗位方向</th>
              <th>综合评分</th>
              <th>评级</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in trainingItems" :key="item.interviewId">
              <td>{{ formatTime(item.interviewTime) }}</td>
              <td>{{ item.position }}</td>
              <td class="score-cell">{{ item.overallScore.toFixed(1) }}</td>
              <td><span :class="['badge', scoreClass(item.overallScore)]">{{ ratingOf(item.overallScore) }}</span></td>
              <td>
                <button class="secondary small" @click="router.push(`/report/${item.interviewId}`)">查看报告</button>
              </td>
            </tr>
          </tbody>
          </table>
        </div>
        <p v-else class="empty">暂无训练模式的面试记录</p>
      </div>

      <div class="card section">
        <div class="section-head">
          <h2>面试记录 · 实战模式（最近 5 条）</h2>
          <button v-if="practiceTotal > 5" class="link-btn" @click="router.push('/history/interviews?tab=practice')">
            查看全部（{{ practiceTotal }} 次） →
          </button>
        </div>
        <div v-if="practiceItems.length" class="table-wrap">
          <table class="history-table">
          <thead>
            <tr>
              <th>面试时间</th>
              <th>岗位方向</th>
              <th>综合评分</th>
              <th>评级</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in practiceItems" :key="item.interviewId">
              <td>{{ formatTime(item.interviewTime) }}</td>
              <td>{{ item.position }}</td>
              <td class="score-cell">{{ item.overallScore.toFixed(1) }}</td>
              <td><span :class="['badge', scoreClass(item.overallScore)]">{{ ratingOf(item.overallScore) }}</span></td>
              <td>
                <button class="secondary small" @click="router.push(`/report/${item.interviewId}`)">查看报告</button>
              </td>
            </tr>
          </tbody>
          </table>
        </div>
        <p v-else class="empty">暂无实战模式的面试记录</p>
      </div>

      <div class="card section">
        <div class="section-head">
          <h2>专项训练记录（最近 5 条）</h2>
          <button v-if="trainingRecordsTotal > 5" class="link-btn" @click="router.push('/history/trainings')">
            查看全部（{{ trainingRecordsTotal }} 次） →
          </button>
        </div>
        <div v-if="trainingRecords.length" class="table-wrap">
          <table class="history-table">
          <thead>
            <tr>
              <th>完成时间</th>
              <th>资料分组</th>
              <th>答题数</th>
              <th>最高难度</th>
              <th>平均得分</th>
              <th>评级</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="record in trainingRecords" :key="record.id">
              <td>{{ formatTime(record.finishedAt) }}</td>
              <td>{{ record.category }}</td>
              <td>{{ record.askedCount }} 题</td>
              <td>{{ difficultyLabel(record.maxDifficulty) }}</td>
              <td class="score-cell">{{ record.averageScore.toFixed(1) }}</td>
              <td><span :class="['badge', scoreClass(record.averageScore / 10)]">{{ ratingOf(record.averageScore) }}</span></td>
              <td>
                <button class="secondary small" @click="router.push(`/training-report/${record.id}`)">查看报告</button>
              </td>
            </tr>
          </tbody>
          </table>
        </div>
        <p v-else class="empty">暂无专项训练记录，先去「专项训练」完成一场吧</p>
      </div>
    </template>
  </div>
</template>

<style scoped>
.section {
  margin-bottom: 16px;
}

.section h2 {
  font-size: 16px;
  margin-bottom: 14px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.link-btn {
  background: none;
  border: none;
  padding: 0;
  color: var(--primary);
  font-size: 13px;
  cursor: pointer;
}

.link-btn:hover {
  text-decoration: underline;
}

.trend {
  height: 260px;
}

.table-wrap {
  overflow-x: auto;
}

.history-table {
  width: 100%;
  min-width: 640px;
  border-collapse: collapse;
}

.history-table th,
.history-table td {
  text-align: left;
  padding: 10px 8px;
  border-bottom: 1px solid var(--border);
}

.history-table th {
  color: var(--text-light);
  font-weight: 500;
  font-size: 13px;
}

.score-cell {
  font-weight: 700;
}

button.small {
  padding: 4px 12px;
  font-size: 13px;
}
</style>
