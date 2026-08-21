<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { trainingApi } from '../api'
import { classifyError } from '../utils/errors'

const router = useRouter()

// 每页 15 条：与面试模拟记录页保持一致的行高与分页体验
const PAGE_SIZE = 15

const page = ref(0)
const loading = ref(true)
const error = ref('')
const items = ref([])
const totalElements = ref(0)
const totalPages = ref(0)

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

function gotoPage(next) {
  if (next < 0 || next >= totalPages.value || next === page.value) {
    return
  }
  page.value = next
}

function pageNumbers() {
  const total = totalPages.value
  if (total <= 5) {
    return Array.from({ length: total }, (_, i) => i)
  }
  let start = Math.max(0, page.value - 2)
  const end = Math.min(total - 1, start + 4)
  start = Math.max(0, end - 4)
  return Array.from({ length: end - start + 1 }, (_, i) => start + i)
}

onMounted(load)
watch(page, load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await trainingApi.records(page.value, PAGE_SIZE)
    items.value = result.content || []
    totalElements.value = result.totalElements || 0
    totalPages.value = result.totalPages || 0
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
      <h1 class="page-title">专项训练记录</h1>
      <button class="secondary" @click="router.push('/history')">← 返回历史报告</button>
    </div>

    <div class="card">
      <template v-if="loading">
        <div class="skeleton skeleton-title"></div>
        <div v-for="i in 6" :key="i" class="skeleton skeleton-row"></div>
      </template>

      <div v-else-if="error" class="error-alert">
        <p class="error-text">{{ error }}</p>
        <button class="secondary" @click="load">重试</button>
      </div>

      <template v-else>
        <div v-if="items.length" class="table-wrap">
          <table class="record-table">
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
              <tr v-for="record in items" :key="record.id">
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

        <div v-if="totalPages > 1" class="pager">
          <button class="secondary small" :disabled="page === 0" @click="gotoPage(page - 1)">上一页</button>
          <button
            v-for="n in pageNumbers()"
            :key="n"
            class="page-num"
            :class="{ active: n === page }"
            @click="gotoPage(n)"
          >
            {{ n + 1 }}
          </button>
          <button class="secondary small" :disabled="page >= totalPages - 1" @click="gotoPage(page + 1)">下一页</button>
          <span class="muted pager-info">共 {{ totalElements }} 条</span>
        </div>
      </template>
    </div>
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

.table-wrap {
  overflow-x: auto;
}

.record-table {
  width: 100%;
  min-width: 720px;
  border-collapse: collapse;
}

.record-table th,
.record-table td {
  text-align: left;
  padding: 10px 8px;
  border-bottom: 1px solid var(--border);
}

.record-table th {
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

.pager {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 16px;
  flex-wrap: wrap;
}

.page-num {
  min-width: 32px;
  padding: 4px 8px;
  border: 1px solid var(--border);
  background: var(--card, #fff);
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
}

.page-num.active {
  border-color: var(--primary);
  color: var(--primary);
  font-weight: 600;
}

.pager-info {
  margin-left: auto;
}
</style>
