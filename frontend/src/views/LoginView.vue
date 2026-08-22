<script setup>
import { onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authApi, setCurrentUser, setToken } from '../api'
import { toast } from '../toast'
import logoUrl from '../assets/logo.png'

const route = useRoute()
const router = useRouter()

// mode：login（账号密码登录）/ register（邮箱验证码注册）/ forgot（忘记密码改密）
const mode = ref('login')
const loading = ref(false)
const error = ref('')

const loginForm = reactive({ username: '', password: '' })
const registerForm = reactive({ email: '', code: '', username: '', password: '' })
const forgotForm = reactive({ email: '', code: '', newPassword: '' })

// 验证码发送：60 秒倒计时（与后端防刷窗口一致），注册与忘记密码共用一个计时器（单表单可见）
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
  if (mode.value === 'register') {
    await submitRegister()
    return
  }
  if (mode.value === 'forgot') {
    await submitForgot()
    return
  }
  if (!loginForm.username.trim() || !loginForm.password) {
    error.value = '请输入用户名/邮箱和密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    // 后端同时支持用户名或邮箱作为登录账号
    const data = await authApi.login(loginForm.username.trim(), loginForm.password)
    onLoginSuccess(data)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function submitRegister() {
  if (!registerForm.email.trim() || !registerForm.code.trim()) {
    error.value = '请输入邮箱和验证码'
    return
  }
  if (!registerForm.username.trim() || !registerForm.password) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await authApi.register(
      registerForm.email.trim(),
      registerForm.code.trim(),
      registerForm.username.trim(),
      registerForm.password
    )
    const data = await authApi.login(registerForm.username.trim(), registerForm.password)
    onLoginSuccess(data)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function submitForgot() {
  if (!forgotForm.email.trim() || !forgotForm.code.trim()) {
    error.value = '请输入邮箱和验证码'
    return
  }
  if (!forgotForm.newPassword || forgotForm.newPassword.length < 6) {
    error.value = '新密码至少 6 位'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await authApi.resetPassword(forgotForm.email.trim(), forgotForm.code.trim(), forgotForm.newPassword)
    toast.success('密码修改成功，请使用新密码登录')
    switchMode('login')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function sendCode() {
  // 当前可见表单的邮箱即为发码目标（注册与忘记密码复用同一接口）
  const email = mode.value === 'register' ? registerForm.email.trim() : forgotForm.email.trim()
  if (!email) {
    error.value = '请输入邮箱'
    return
  }
  sending.value = true
  error.value = ''
  try {
    await authApi.sendCode(email)
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
      <div class="brand-title">
        <img :src="logoUrl" alt="Easy Offer Forge" class="brand-logo" />
      </div>
      <p class="muted subtitle">AI 面试教练 · 让每次面试都有备而来</p>

      <div class="tabs">
        <button :class="{ active: mode === 'login' }" class="secondary" @click="switchMode('login')">登录</button>
        <button :class="{ active: mode === 'register' }" class="secondary" @click="switchMode('register')">注册</button>
      </div>

      <form v-if="mode === 'login'" @submit.prevent="submit">
        <label>
          <span>用户名 / 邮箱</span>
          <input v-model="loginForm.username" placeholder="请输入用户名或邮箱" autocomplete="username" />
        </label>
        <label>
          <span>密码</span>
          <input
            v-model="loginForm.password"
            type="password"
            placeholder="请输入密码"
            autocomplete="current-password"
          />
        </label>
        <p v-if="error" class="error-text">{{ error }}</p>
        <button type="submit" class="submit" :disabled="loading">
          {{ loading ? '处理中…' : '登录' }}
        </button>
        <p class="forgot-link">
          <a href="#" @click.prevent="switchMode('forgot')">忘记密码？</a>
        </p>
      </form>

      <form v-else-if="mode === 'register'" @submit.prevent="submit">
        <label>
          <span>邮箱</span>
          <input v-model="registerForm.email" type="email" placeholder="请输入邮箱" autocomplete="email" />
        </label>
        <div class="code-row">
          <label>
            <span>验证码</span>
            <input
              v-model="registerForm.code"
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
        <label>
          <span>用户名</span>
          <input v-model="registerForm.username" placeholder="3-32 位" autocomplete="username" />
        </label>
        <label>
          <span>密码</span>
          <input
            v-model="registerForm.password"
            type="password"
            placeholder="至少 6 位"
            autocomplete="new-password"
          />
        </label>
        <p v-if="error" class="error-text">{{ error }}</p>
        <button type="submit" class="submit" :disabled="loading">
          {{ loading ? '处理中…' : '注册并登录' }}
        </button>
        <p class="muted email-tip">每个邮箱仅可注册一个账号</p>
      </form>

      <form v-else @submit.prevent="submit">
        <label>
          <span>邮箱</span>
          <input v-model="forgotForm.email" type="email" placeholder="请输入注册邮箱" autocomplete="email" />
        </label>
        <div class="code-row">
          <label>
            <span>验证码</span>
            <input
              v-model="forgotForm.code"
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
        <label>
          <span>新密码</span>
          <input
            v-model="forgotForm.newPassword"
            type="password"
            placeholder="至少 6 位"
            autocomplete="new-password"
          />
        </label>
        <p v-if="error" class="error-text">{{ error }}</p>
        <button type="submit" class="submit" :disabled="loading">
          {{ loading ? '处理中…' : '修改密码' }}
        </button>
        <p class="forgot-link">
          <a href="#" @click.prevent="switchMode('login')">返回登录</a>
        </p>
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
  text-align: center;
  margin-bottom: 8px;
}

.brand-logo {
  display: block;
  height: 48px;
  width: auto;
  margin: 0 auto;
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

.forgot-link {
  text-align: center;
  font-size: 13px;
  margin-top: 10px;
}

.forgot-link a {
  color: var(--primary);
  text-decoration: none;
}
</style>
