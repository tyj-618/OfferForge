<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authApi, clearToken, getToken } from './api'

const route = useRoute()
const router = useRouter()
const loggedIn = computed(() => !!getToken() && route.name !== 'login')

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
      <div class="brand">🎯 OfferForge</div>
      <nav class="nav">
        <RouterLink to="/">问答练习</RouterLink>
        <RouterLink to="/interview">模拟面试</RouterLink>
        <RouterLink to="/resume">简历管理</RouterLink>
        <RouterLink to="/history">历史报告</RouterLink>
      </nav>
      <button class="ghost" @click="logout">退出登录</button>
    </header>
    <RouterView />
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

.nav a.router-link-active {
  color: var(--primary);
}
</style>
