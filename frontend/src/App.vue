<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ToastHub from './components/ToastHub.vue'
import { adminApi, authApi, authState, billingState, clearToken, currentUser, fetchCurrentUser, refreshBillingState } from './api'
// 品牌横版 logo（透明底）：替代旧版 emoji + 文字品牌区，Vite 构建时指纹化到 dist
import logoUrl from './assets/logo.png'

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

// 管理台入口仅管理员可见：登录态变化时经 whoami 重新认定；未登录/非管理员隐藏入口项，
// 避免非管理员看到无效入口；手动直访 /admin 由后端 40300 拒绝兼做兜底。
const isAdmin = ref(false)

function refreshAdminFlag() {
  if (!authState.token) {
    isAdmin.value = false
    return
  }
  adminApi
    .whoami()
    .then((result) => {
      isAdmin.value = !!result?.admin
    })
    .catch(() => {
      isAdmin.value = false
    })
}

watch(() => authState.token, refreshAdminFlag, { immediate: true })

// 充值入口仅总开关开启时可见：登录态变化时重新拉取计费状态；登出即复位隐藏。
watch(
  () => authState.token,
  (token) => {
    if (token) {
      refreshBillingState()
    } else {
      billingState.enabled = false
      billingState.balanceCents = 0
    }
  },
  { immediate: true }
)

// 报告详情需 interviewId 参数，从历史记录列表进入；报告详情路由也高亮历史记录
const baseNavItems = [
  { to: '/interview', label: '模拟面试', routes: ['interview'] },
  { to: '/training', label: '专项训练', routes: ['training'] },
  { to: '/', label: '快捷提问', routes: ['qa'] },
  { to: '/history', label: '历史记录', routes: ['history', 'report'] },
  { to: '/resume', label: '简历', routes: ['resume'] },
  { to: '/library', label: '资源库', routes: ['library'] },
  { to: '/docs', label: '文档', routes: ['docs'] },
  { to: '/settings', label: '设置', routes: ['settings'] }
]

// 充值入口常驻展示（设置之前）：页面完整可进，仅充值按钮在审核期提示；管理台入口动态追加，非管理员不可见
const navItems = computed(() => {
  const items = [...baseNavItems]
  items.splice(items.length - 1, 0, {
    to: '/billing',
    label: '充值',
    routes: ['billing']
  })
  if (isAdmin.value) {
    items.push({ to: '/admin', label: '管理台', routes: ['admin'] })
  }
  return items
})

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
      <div class="brand">
        <img :src="logoUrl" alt="Easy Offer Forge" class="brand-logo" />
      </div>
      <nav class="nav">
        <template v-for="item in navItems" :key="item.label">
          <RouterLink :to="item.to" :class="{ 'is-active': isActive(item) }">
            {{ item.label }}
          </RouterLink>
        </template>
      </nav>
      <span v-if="displayUsername" class="user-chip">👤 {{ displayUsername }}</span>
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

.brand-logo {
  display: block;
  height: 30px;
  width: auto;
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
    gap: 6px 12px;
    padding: 10px 14px;
  }

  .brand {
    font-size: 16px;
  }

  .brand-logo {
    height: 24px;
  }

  /* 移动端导航单行横向滑动，避免 8 个入口折行显得杂乱 */
  .nav {
    gap: 16px;
    font-size: 13px;
    order: 3;
    flex-basis: 100%;
    flex-wrap: nowrap;
    overflow-x: auto;
    padding-bottom: 4px;
    -webkit-overflow-scrolling: touch;
    scrollbar-width: none;
  }

  .nav::-webkit-scrollbar {
    display: none;
  }

  .nav a {
    flex-shrink: 0;
    white-space: nowrap;
  }

  /* 移动端保留账号名：收窄并超长截断，避免挤掉退出按钮 */
  .user-chip {
    max-width: 108px;
    overflow: hidden;
    text-overflow: ellipsis;
    font-size: 12px;
  }
}
</style>
