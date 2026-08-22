import { createRouter, createWebHistory } from 'vue-router'
import { billingState, getToken } from '../api'
import { toast } from '../toast'

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
  // 充值中心：余额/充值档位/模型价目/订单与流水；审核期入口常驻展示，点击/直访提示审核中（下方守卫）
  { path: '/billing', name: 'billing', component: () => import('../views/BillingView.vue') },
  // 管理台：仅管理员可见入口（顶栏按 /api/admin/whoami 认定），非管理员直接访问由后端 40300 拒绝
  { path: '/admin', name: 'admin', component: () => import('../views/AdminView.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 未登录统一跳登录页；充值页在计费开关关闭（审核期）时拦截直访并提示
router.beforeEach((to) => {
  if (!to.meta.public && !getToken()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'billing' && billingState.loaded && !billingState.enabled) {
    toast.info('相关功能正在审核中，敬请期待')
    return false
  }
})

export default router
