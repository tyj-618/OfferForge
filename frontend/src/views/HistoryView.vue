<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { reportApi } from '../api'
import { classifyError } from '../utils/errors'

const router = useRouter()
const loading = ref(true)
const error = ref('')
const historyItems = ref([])
const totalElements = ref(0)
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

function renderTrend() {
  if (!trendEl.value) {
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
    const [history, progress] = await Promise.all([
      reportApi.history(0, 10),
      reportApi.progress(10)
    ])
    historyItems.value = history.content || []
    totalElements.value = history.totalElements || 0
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
        <div v-if="progressPoints.length" ref="trendEl" class="trend"></div>
        <p v-else class="empty">暂无面试记录，先去「模拟面试」完成一场吧</p>
      </div>

      <div class="card section">
        <h2>面试记录（共 {{ totalElements }} 次）</h2>
        <div v-if="historyItems.length" class="table-wrap">
          <table class="history-table">
          <thead>
            <tr>
              <th>面试时间</th>
              <th>岗位方向</th>
              <th>综合评分</th>
              <th>评级</th>
              <th>状态</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in historyItems" :key="item.interviewId">
              <td>{{ formatTime(item.interviewTime) }}</td>
              <td>{{ item.position }}</td>
              <td class="score-cell">{{ item.overallScore.toFixed(1) }}</td>
              <td><span :class="['badge', scoreClass(item.overallScore)]">{{ ratingOf(item.overallScore) }}</span></td>
              <td><span class="badge success">{{ item.status }}</span></td>
              <td>
                <button class="secondary small" @click="router.push(`/report/${item.interviewId}`)">查看报告</button>
              </td>
            </tr>
          </tbody>
          </table>
        </div>
        <p v-if="!historyItems.length" class="empty">暂无面试记录</p>
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
