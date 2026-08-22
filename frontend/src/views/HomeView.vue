<script setup>
import { useRouter } from 'vue-router'
import { reportApi } from '../api'
import { ref, onMounted } from 'vue'

const router = useRouter()
const recentReports = ref([])
const loading = ref(true)

onMounted(async () => {
  try {
    const history = await reportApi.history(0, 3)
    recentReports.value = history?.content || []
  } catch {
    recentReports.value = []
  } finally {
    loading.value = false
  }
})

const quickActions = [
  {
    title: '模拟面试',
    desc: '围绕你的简历和知识库出题，实时追问、动态调难',
    route: '/interview',
    icon: '🎯',
    color: '#4f6ef7'
  },
  {
    title: '专项训练',
    desc: '按知识分组强化训练，即时评分与导师反馈',
    route: '/training',
    icon: '📚',
    color: '#16a34a'
  },
  {
    title: '资源库',
    desc: '官方 318 题题库 + 上传个人面经笔记',
    route: '/library',
    icon: '️',
    color: '#d97706'
  }
]

function goTo(route) {
  router.push(route)
}

function scoreClass(score) {
  if (score >= 85) return 'success'
  if (score >= 60) return 'warning'
  return 'danger'
}
</script>

<template>
  <div class="page home-page">
    <section class="hero">
      <h1 class="hero-title">让每次面试都有备而来</h1>
      <p class="hero-desc">
        基于 LLM 的 AI 面试教练：围绕你的简历与知识库出题，实时追问、多维评分，生成可追踪的进步报告。
      </p>
    </section>

    <section class="quick-actions">
      <div v-for="action in quickActions" :key="action.route" class="action-card card" @click="goTo(action.route)">
        <div class="action-icon" :style="{ background: action.color + '15', color: action.color }">
          {{ action.icon }}
        </div>
        <div class="action-info">
          <h3>{{ action.title }}</h3>
          <p>{{ action.desc }}</p>
        </div>
        <div class="action-arrow">→</div>
      </div>
    </section>

    <section v-if="recentReports.length > 0" class="recent-section">
      <div class="section-header">
        <h2>最近面试</h2>
        <a href="/history" class="section-link">查看全部 →</a>
      </div>
      <div class="recent-list">
        <div v-for="item in recentReports" :key="item.interviewId" class="recent-item card" @click="goTo('/report/' + item.interviewId)">
          <div class="recent-info">
            <span class="recent-mode" :class="item.mode === 'training' ? 'training' : 'practice'">
              {{ item.mode === 'training' ? '训练' : '实战' }}
            </span>
            <span class="recent-position">{{ item.position || '未设置岗位' }}</span>
          </div>
          <div class="recent-score">
            <span class="score-value" :class="scoreClass(item.overallScore)">
              {{ item.overallScore?.toFixed(0) || '—' }}
            </span>
            <span class="score-label">分</span>
          </div>
        </div>
      </div>
    </section>

    <section v-else-if="!loading" class="empty-hint">
      <p>还没有面试记录，从上方选择一个入口开始吧</p>
    </section>
  </div>
</template>

<style scoped>
.hero {
  text-align: center;
  padding: 20px 0 32px;
}

.hero-title {
  font-size: 28px;
  font-weight: 700;
  margin-bottom: 12px;
}

.hero-desc {
  font-size: 15px;
  color: var(--text-light);
  max-width: 520px;
  margin: 0 auto;
  line-height: 1.7;
}

.quick-actions {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 32px;
}

.action-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 18px 20px;
  cursor: pointer;
  transition: box-shadow 0.2s, transform 0.15s;
}

.action-card:hover {
  box-shadow: 0 4px 16px rgba(79, 110, 247, 0.1);
  transform: translateY(-1px);
}

.action-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  flex-shrink: 0;
}

.action-info {
  flex: 1;
  min-width: 0;
}

.action-info h3 {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 4px;
}

.action-info p {
  font-size: 13px;
  color: var(--text-light);
  line-height: 1.5;
}

.action-arrow {
  color: var(--text-light);
  font-size: 18px;
  flex-shrink: 0;
}

.recent-section {
  margin-bottom: 20px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.section-header h2 {
  font-size: 18px;
  font-weight: 700;
}

.section-link {
  font-size: 14px;
  color: var(--primary);
}

.recent-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.recent-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 18px;
  cursor: pointer;
  transition: box-shadow 0.2s;
}

.recent-item:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.recent-info {
  display: flex;
  align-items: center;
  gap: 10px;
}

.recent-mode {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 999px;
  font-weight: 600;
}

.recent-mode.training {
  background: #eef1ff;
  color: var(--primary);
}

.recent-mode.practice {
  background: #fef3c7;
  color: var(--warning);
}

.recent-position {
  font-size: 14px;
  font-weight: 500;
}

.recent-score {
  display: flex;
  align-items: baseline;
  gap: 2px;
}

.score-value {
  font-size: 22px;
  font-weight: 700;
}

.score-value.success { color: var(--success); }
.score-value.warning { color: var(--warning); }
.score-value.danger { color: var(--danger); }

.score-label {
  font-size: 12px;
  color: var(--text-light);
}

.empty-hint {
  text-align: center;
  padding: 24px 0;
  color: var(--text-light);
  font-size: 14px;
}

@media (max-width: 767px) {
  .hero-title {
    font-size: 22px;
  }

  .hero-desc {
    font-size: 14px;
  }

  .action-card {
    padding: 14px 16px;
  }

  .action-icon {
    width: 42px;
    height: 42px;
    font-size: 20px;
  }
}
</style>
