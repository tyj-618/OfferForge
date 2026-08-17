<script setup>
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ToastHub from './components/ToastHub.vue'
import { authApi, authState, clearToken, currentUser, fetchCurrentUser } from './api'

const route = useRoute()
const router = useRouter()
// 依赖响应式 authState：登录后顶栏立即出现，登出/401 后立即消失
const loggedIn = computed(() => !!authState.token && route.name !== 'login')

// 顶栏用户名：优先显示注册时用户输入的用户名；昵称仅作兜底（注册时默认为 Candidate_xxx）
const displayUsername = computed(() => currentUser.username || currentUser.nickname || '')

// 刷新恢复：token 在而登录缓存丢失时，经 /api/auth/me 补齐用户名
onMounted(() => {
  if (authState.token && !currentUser.username) {
    fetchCurrentUser().catch(() => {})
  }
})

// 报告详情需 interviewId 参数，从历史记录列表进入；报告详情路由也高亮历史记录
const navItems = [
  { to: '/interview', label: '模拟面试', routes: ['interview'] },
  { to: '/training', label: '专项训练', routes: ['training'] },
  { to: '/', label: '快捷提问', routes: ['qa'] },
  { to: '/history', label: '历史记录', routes: ['history', 'report'] },
  { to: '/resume', label: '简历', routes: ['resume'] },
  { to: '/knowledge', label: '资料库', routes: ['knowledge'] },
  { to: '/docs', label: '文档', routes: ['docs'] },
  { to: '/settings', label: '设置', routes: ['settings'] }
]

// 按路由名精确匹配高亮，避免 vue-router 对 "/" 的前缀匹配导致快捷提问在所有页面常亮
function isActive(item) {
  return item.routes.includes(route.name)
}

async function logout() {
  try {
    await authApi.logout()
  } catch {
    // 登出失败也清本地 token
  }
  clearToken()
  router.push({ name: 'login' })
}
</script>

<template>
  <div class="app">
    <header v-if="loggedIn" class="topbar">
      <div class="brand">🎯 Easy Offer Forge</div>
      <nav class="nav">
        <RouterLink
          v-for="item in navItems"
          :key="item.label"
          :to="item.to"
          :class="{ 'is-active': isActive(item) }"
        >
          {{ item.label }}
        </RouterLink>
      </nav>
      <span v-if="displayUsername" class="user-chip">👤 {{ displayUsername }} |</span>
      <button class="ghost" @click="logout">退出登录</button>
    </header>
    <RouterView />
    <ToastHub />
  </div>
</template>

<style scoped>
.topbar {
  display: flex;
  align-items: center;
  gap: 32px;
  padding: 14px 28px;
  background: #fff;
  border-bottom: 1px solid var(--border);
  position: sticky;
  top: 0;
  z-index: 10;
}

.brand {
  font-size: 18px;
  font-weight: 700;
}

.nav {
  display: flex;
  gap: 24px;
  flex: 1;
}

.nav a {
  color: var(--text-light);
  font-weight: 500;
}

.nav a.is-active {
  color: var(--primary);
}

.user-chip {
  font-size: 14px;
  color: var(--text-light);
  white-space: nowrap;
}

@media (max-width: 767px) {
  .topbar {
    flex-wrap: wrap;
    gap: 8px 16px;
    padding: 10px 16px;
  }

  .nav {
    gap: 14px;
    font-size: 13px;
    order: 3;
    flex-basis: 100%;
  }
}
</style>
