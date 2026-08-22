import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../api'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
  { path: '/', name: 'qa', component: () => import('../views/QaView.vue') },
  { path: '/interview', name: 'interview', component: () => import('../views/InterviewView.vue') },
  { path: '/training', name: 'training', component: () => import('../views/TrainingView.vue') },
  { path: '/report/:interviewId', name: 'report', component: () => import('../views/ReportView.vue') },
  { path: '/resume', name: 'resume', component: () => import('../views/ResumeView.vue') },
  { path: '/library', name: 'library', component: () => import('../views/LibraryView.vue') },
  { path: '/docs', name: 'docs', component: () => import('../views/DocsView.vue') },
  { path: '/history', name: 'history', component: () => import('../views/HistoryView.vue') },
  // 面试模拟记录专属页：按训练/实战模式分页展示全部已完成场次
  { path: '/history/interviews', name: 'interviewRecords', component: () => import('../views/InterviewRecordsView.vue') },
  // 专项训练记录专属页：分页展示全部训练归档
  { path: '/history/trainings', name: 'trainingRecords', component: () => import('../views/TrainingRecordsView.vue') },
  // 训练报告详情：概要 + 逐题明细，支持查看/打印
  { path: '/training-report/:id', name: 'trainingReport', component: () => import('../views/TrainingReportView.vue') },
  { path: '/settings', name: 'settings', component: () => import('../views/SettingsView.vue') },
  // 充值中心：余额/充值档位/模型价目/订单与流水；页面完整可进，审核期仅充值按钮提示审核中
  { path: '/billing', name: 'billing', component: () => import('../views/BillingView.vue') },
  // 管理台：仅管理员可见入口（顶栏按 /api/admin/whoami 认定），非管理员直接访问由后端 40300 拒绝
  { path: '/admin', name: 'admin', component: () => import('../views/AdminView.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 未登录统一跳登录页
router.beforeEach((to) => {
  if (!to.meta.public && !getToken()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
})

export default router
