<script setup>
import { onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authApi, setCurrentUser, setToken } from '../api'
import { toast } from '../toast'

const route = useRoute()
const router = useRouter()

const mode = ref('login')
const loading = ref(false)
const error = ref('')
const form = reactive({ username: '', password: '' })

// 邮箱验证码登录：发码后 60 秒倒计时（与后端防刷窗口一致）
const emailForm = reactive({ email: '', code: '' })
const sending = ref(false)
const countdown = ref(0)
let countdownTimer = null

function onLoginSuccess(data) {
  setToken(data.token)
  // 缓存登录响应的用户信息，供顶栏展示用户名（刷新恢复时降级经 /api/auth/me 补齐）
  setCurrentUser(data.user)
  router.push(route.query.redirect || '/')
}

async function submit() {
  if (mode.value === 'email') {
    await submitEmailCode()
    return
  }
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
    onLoginSuccess(data)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function submitEmailCode() {
  if (!emailForm.email.trim() || !emailForm.code.trim()) {
    error.value = '请输入邮箱和验证码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const data = await authApi.loginByCode(emailForm.email.trim(), emailForm.code.trim())
    onLoginSuccess(data)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function sendCode() {
  if (!emailForm.email.trim()) {
    error.value = '请输入邮箱'
    return
  }
  sending.value = true
  error.value = ''
  try {
    await authApi.sendCode(emailForm.email.trim())
    toast.success('验证码已发送，请注意查收邮箱')
    startCountdown()
  } catch (e) {
    error.value = e.message
  } finally {
    sending.value = false
  }
}

function startCountdown() {
  countdown.value = 60
  clearInterval(countdownTimer)
  countdownTimer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      clearInterval(countdownTimer)
    }
  }, 1000)
}

onUnmounted(() => clearInterval(countdownTimer))

function switchMode(target) {
  mode.value = target
  error.value = ''
}
</script>

<template>
  <div class="login-page">
    <div class="login-card card">
      <div class="brand-title">🎯 Easy Offer Forge</div>
      <p class="muted subtitle">AI 面试教练 · 让每次面试都有备而来</p>

      <div class="tabs">
        <button :class="{ active: mode === 'login' }" class="secondary" @click="switchMode('login')">登录</button>
        <button :class="{ active: mode === 'register' }" class="secondary" @click="switchMode('register')">注册</button>
        <button :class="{ active: mode === 'email' }" class="secondary" @click="switchMode('email')">邮箱登录</button>
      </div>

      <form v-if="mode === 'email'" @submit.prevent="submit">
        <label>
          <span>邮箱</span>
          <input v-model="emailForm.email" type="email" placeholder="请输入邮箱" autocomplete="email" />
        </label>
        <div class="code-row">
          <label>
            <span>验证码</span>
            <input
              v-model="emailForm.code"
              maxlength="6"
              inputmode="numeric"
              placeholder="6 位数字验证码"
              autocomplete="one-time-code"
            />
          </label>
          <button type="button" class="secondary send-code" :disabled="sending || countdown > 0" @click="sendCode">
            {{ sending ? '发送中…' : countdown > 0 ? `${countdown}s 后重发` : '发送验证码' }}
          </button>
        </div>
        <p v-if="error" class="error-text">{{ error }}</p>
        <button type="submit" class="submit" :disabled="loading">
          {{ loading ? '处理中…' : '登录 / 注册' }}
        </button>
        <p class="muted email-tip">未注册的邮箱验证通过后自动创建账号</p>
      </form>

      <form v-else @submit.prevent="submit">
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

.code-row {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  margin-bottom: 14px;
}

.code-row label {
  flex: 1;
  margin-bottom: 0;
}

.send-code {
  white-space: nowrap;
  padding: 9px 12px;
  min-width: 118px;
}

.email-tip {
  text-align: center;
  font-size: 12px;
  margin-top: 10px;
}
</style>
