<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { trainingApi } from '../api'
import { classifyError } from '../utils/errors'

const route = useRoute()
const router = useRouter()
const report = ref(null)
const loading = ref(true)
const error = ref('')
const expanded = ref({})

const ratingClass = computed(() => {
  const rating = report.value?.rating
  if (rating === '优秀') return 'success'
  if (rating === '良好') return ''
  if (rating === '及格') return 'warning'
  return 'danger'
})

function formatTime(value) {
  return value ? new Date(value).toLocaleString('zh-CN') : '—'
}

function difficultyLabel(value) {
  if (value === 'HARD') return '困难'
  if (value === 'MEDIUM') return '中等'
  return '简单'
}

function toggle(index) {
  expanded.value[index] = !expanded.value[index]
}

// 导出 PDF：展开全部逐题点评后走浏览器打印（全局打印样式自动隐藏顶栏与按钮）
function exportPdf() {
  for (let i = 0; i < (report.value?.details || []).length; i++) {
    expanded.value[i] = true
  }
  nextTick(() => window.print())
}

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    report.value = await trainingApi.recordReport(route.params.id)
  } catch (e) {
    error.value = classifyError(e).message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h1 class="page-title">专项训练报告</h1>
      <div class="head-actions">
        <button v-if="report" class="secondary print-btn" @click="exportPdf">🖨 导出 PDF</button>
        <button class="secondary print-hidden" @click="router.back()">← 返回</button>
      </div>
    </div>

    <template v-if="loading">
      <div class="card section">
        <div class="skeleton skeleton-title"></div>
        <div class="skeleton skeleton-chart"></div>
      </div>
    </template>

    <div v-else-if="error" class="card section error-alert">
      <p class="error-text">{{ error }}</p>
      <button class="secondary" @click="load">重试</button>
    </div>

    <template v-else-if="report">
      <!-- 顶部概要 -->
      <div class="card overview">
        <div class="score-block">
          <div class="big-score">{{ report.averageScore.toFixed(1) }}</div>
          <div class="muted">平均得分（满分 100）</div>
          <span :class="['badge', ratingClass]">{{ report.rating }}</span>
        </div>
        <div class="meta">
          <p><span class="muted">资料分组：</span>{{ report.category }}</p>
          <p><span class="muted">完成时间：</span>{{ formatTime(report.finishedAt) }}</p>
          <p>
            <span class="muted">题量：</span>{{ report.askedCount }} 题 ·
            最高难度 {{ difficultyLabel(report.maxDifficulty) }} ·
            用时 {{ report.durationMinutes }} 分钟
          </p>
        </div>
      </div>

      <!-- 逐题点评 -->
      <div class="card section">
        <h2>逐题点评</h2>
        <template v-if="report.details.length">
          <div v-for="(item, index) in report.details" :key="index" class="question-item">
            <div class="question-header" @click="toggle(index)">
              <span class="question-index">Q{{ index + 1 }}</span>
              <span v-if="item.knowledgePoint" class="badge">{{ item.knowledgePoint }}</span>
              <span class="question-text">{{ item.question }}</span>
              <span :class="['badge', item.score >= 7 ? 'success' : item.score >= 4 ? 'warning' : 'danger']">
                {{ item.score.toFixed(1) }} 分
              </span>
              <span class="expand-icon">{{ expanded[index] ? '▾' : '▸' }}</span>
            </div>
            <div v-if="expanded[index]" class="question-detail">
              <p><span class="muted">你的回答：</span>{{ item.answer || '（未作答）' }}</p>
              <p v-if="item.comment"><span class="muted">导师点评：</span>{{ item.comment }}</p>
              <template v-if="item.evaluation">
                <div v-if="item.evaluation.goodPoints?.length" class="eval-block">
                  <div class="eval-title good">✨ 亮点</div>
                  <ul><li v-for="(point, i) in item.evaluation.goodPoints" :key="'g' + i">{{ point }}</li></ul>
                </div>
                <div v-if="item.evaluation.badPoints?.length" class="eval-block">
                  <div class="eval-title bad">⚠️ 不足</div>
                  <ul><li v-for="(point, i) in item.evaluation.badPoints" :key="'b' + i">{{ point }}</li></ul>
                </div>
                <div v-if="item.evaluation.missedPoints?.length" class="eval-block">
                  <div class="eval-title missed">📌 遗漏要点</div>
                  <ul><li v-for="(point, i) in item.evaluation.missedPoints" :key="'m' + i">{{ point }}</li></ul>
                </div>
                <div v-if="item.evaluation.improvedAnswer" class="eval-block">
                  <div class="eval-title improved">💡 改进回答参考</div>
                  <p class="improved-text">{{ item.evaluation.improvedAnswer }}</p>
                </div>
              </template>
            </div>
          </div>
        </template>
        <p v-else class="muted">该记录归档较早，未保存逐题明细，仅可查看概要成绩。</p>
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

.head-actions {
  display: flex;
  gap: 8px;
}

.overview {
  display: grid;
  grid-template-columns: 220px 1fr;
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

.section {
  margin-bottom: 16px;
}

.section h2 {
  font-size: 16px;
  margin-bottom: 14px;
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

.question-detail > p {
  margin-bottom: 8px;
}

.eval-block {
  background: #f6f8fd;
  border-radius: 8px;
  padding: 10px 14px;
  margin-bottom: 8px;
}

.eval-block ul {
  margin: 6px 0 0;
  padding-left: 18px;
}

.eval-block li {
  margin-bottom: 4px;
}

.eval-title {
  font-weight: 600;
  font-size: 13px;
}

.eval-title.good {
  color: #16a34a;
}

.eval-title.bad {
  color: #dc2626;
}

.eval-title.missed {
  color: #d97706;
}

.eval-title.improved {
  color: var(--primary);
}

.improved-text {
  margin: 6px 0 0;
  white-space: pre-wrap;
}

@media (max-width: 767px) {
  .overview {
    grid-template-columns: 1fr;
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
}

@media print {
  .print-hidden {
    display: none !important;
  }
}
</style>
