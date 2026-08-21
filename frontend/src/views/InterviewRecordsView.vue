<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { reportApi } from '../api'
import { classifyError } from '../utils/errors'

const route = useRoute()
const router = useRouter()

// 每页 15 条：表格行高约 45px，15 行 + 分页栏在常规视口内基本完整可见
const PAGE_SIZE = 15

// tab 由路由参数驱动：training 训练模式 / practice 实战模式（默认训练）
const tab = ref(route.query.tab === 'practice' ? 'practice' : 'training')
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

function switchTab(next) {
  if (tab.value === next) {
    return
  }
  tab.value = next
  page.value = 0
  router.replace({ query: { tab: next } })
}

function gotoPage(next) {
  if (next < 0 || next >= totalPages.value || next === page.value) {
    return
  }
  page.value = next
}

// 页码按钮：围绕当前页取最多 5 个连续页码
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
watch([tab, page], load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await reportApi.history(page.value, PAGE_SIZE, tab.value)
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
      <h1 class="page-title">面试模拟记录</h1>
      <button class="secondary" @click="router.push('/history')">← 返回历史报告</button>
    </div>

    <div class="card">
      <div class="tabs">
        <button
          class="tab"
          :class="{ active: tab === 'training' }"
          @click="switchTab('training')"
        >
          训练模式
        </button>
        <button
          class="tab"
          :class="{ active: tab === 'practice' }"
          @click="switchTab('practice')"
        >
          实战模式
        </button>
      </div>

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
                <th>面试时间</th>
                <th>岗位方向</th>
                <th>综合评分</th>
                <th>评级</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in items" :key="item.interviewId">
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
        <p v-else class="empty">
          暂无{{ tab === 'training' ? '训练模式' : '实战模式' }}的面试记录，先去「模拟面试」完成一场吧
        </p>

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

.tabs {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid var(--border);
  margin-bottom: 14px;
}

.tab {
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  padding: 10px 18px;
  font-size: 15px;
  color: var(--text-light);
  cursor: pointer;
}

.tab.active {
  color: var(--primary);
  border-bottom-color: var(--primary);
  font-weight: 600;
}

.table-wrap {
  overflow-x: auto;
}

.record-table {
  width: 100%;
  min-width: 640px;
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
