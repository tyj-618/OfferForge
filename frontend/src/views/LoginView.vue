<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authApi, setCurrentUser, setToken } from '../api'

const route = useRoute()
const router = useRouter()

const mode = ref('login')
const loading = ref(false)
const error = ref('')
const form = reactive({ username: '', password: '' })

async function submit() {
  if (!form.username.trim() || !form.password) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    if (mode.value === 'register') {
      await authApi.register(form.username.trim(), form.password)
    }
    const data = await authApi.login(form.username.trim(), form.password)
    setToken(data.token)
    // 缓存登录响应的用户信息，供顶栏展示用户名（刷新恢复时降级经 /api/auth/me 补齐）
    setCurrentUser(data.user)
    router.push(route.query.redirect || '/')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function switchMode(target) {
  mode.value = target
  error.value = ''
}
</script>

<template>
  <div class="login-page">
    <div class="login-card card">
      <div class="brand-title">🎯 OfferForge</div>
      <p class="muted subtitle">AI 面试教练 · 让每次面试都有备而来</p>

      <div class="tabs">
        <button :class="{ active: mode === 'login' }" class="secondary" @click="switchMode('login')">登录</button>
        <button :class="{ active: mode === 'register' }" class="secondary" @click="switchMode('register')">注册</button>
      </div>

      <form @submit.prevent="submit">
        <label>
          <span>用户名</span>
          <input v-model="form.username" placeholder="请输入用户名" autocomplete="username" />
        </label>
        <label>
          <span>密码</span>
          <input
            v-model="form.password"
            type="password"
            placeholder="请输入密码（至少 6 位）"
            autocomplete="current-password"
          />
        </label>
        <p v-if="error" class="error-text">{{ error }}</p>
        <button type="submit" class="submit" :disabled="loading">
          {{ loading ? '处理中…' : mode === 'login' ? '登录' : '注册并登录' }}
        </button>
      </form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.login-card {
  width: 380px;
  padding: 36px 32px;
}

.brand-title {
  font-size: 24px;
  font-weight: 700;
  text-align: center;
}

.subtitle {
  text-align: center;
  margin: 6px 0 24px;
}

.tabs {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}

.tabs button {
  flex: 1;
}

.tabs button.active {
  background: var(--primary);
  color: #fff;
}

label {
  display: block;
  margin-bottom: 14px;
}

label span {
  display: block;
  font-size: 13px;
  color: var(--text-light);
  margin-bottom: 6px;
}

.submit {
  width: 100%;
  margin-top: 6px;
  padding: 10px;
}
</style>
