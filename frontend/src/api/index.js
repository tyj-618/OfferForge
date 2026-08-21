import axios from 'axios'
import { reactive } from 'vue'

const TOKEN_KEY = 'offerforge_token'
const USER_KEY = 'offerforge_user'

/**
 * 响应式认证状态：localStorage 读写本身非响应式，
 * 登录/登出/续期/401 均通过 setToken/clearToken 同步此状态，
 * 依赖它的组件（如 App.vue 顶栏）能即时更新。
 */
export const authState = reactive({
  token: localStorage.getItem(TOKEN_KEY) || ''
})

/** 响应式当前用户：登录时缓存 login 响应的 user，刷新恢复时经 /api/auth/me 补齐 */
export const currentUser = reactive({ username: '', nickname: '' })

export function setCurrentUser(user) {
  currentUser.username = user?.username || ''
  currentUser.nickname = user?.nickname || ''
  if (user) {
    localStorage.setItem(USER_KEY, JSON.stringify(user))
  }
}

function restoreCachedUser() {
  try {
    const cached = localStorage.getItem(USER_KEY)
    if (cached) {
      const user = JSON.parse(cached)
      currentUser.username = user?.username || ''
      currentUser.nickname = user?.nickname || ''
    }
  } catch {
    localStorage.removeItem(USER_KEY)
  }
}
restoreCachedUser()

export function clearCurrentUser() {
  currentUser.username = ''
  currentUser.nickname = ''
  localStorage.removeItem(USER_KEY)
}

export function fetchCurrentUser() {
  return authApi.me().then((user) => {
    setCurrentUser(user)
    return user
  })
}

export function getToken() {
  return authState.token
}

export function setToken(token) {
  authState.token = token || ''
  localStorage.setItem(TOKEN_KEY, authState.token)
}

export function clearToken() {
  authState.token = ''
  localStorage.removeItem(TOKEN_KEY)
  clearCurrentUser()
  // 快捷提问历史仅在当前登录期间临时保存，退出登录即清除；训练会话指针同理
  sessionStorage.removeItem('offerforge_qa_session')
  sessionStorage.removeItem('offerforge_training_session')
}

/**
 * 统一 JSON 客户端：解包 ApiResponse，code!=0 抛业务错误；
 * 40100（token 失效）时借助 httpOnly refresh cookie 静默续期并重试一次。
 */
const http = axios.create({ baseURL: '/api', timeout: 60000 })

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

let refreshing = null

http.interceptors.response.use(
  (response) => unwrap(response, false),
  async (error) => {
    if (!error.response) {
      const networkError = new Error('网络连接失败，请检查网络后重试')
      networkError.isNetwork = true
      throw networkError
    }
    return unwrap(error.response, true)
  }
)

async function unwrap(response, retried) {
  const body = response.data
  if (body && body.code === 0) {
    return body.data
  }
  const code = body?.code ?? -1
  const message = body?.message ?? '请求失败'
  if (code === 40100 && !retried) {
    try {
      await refreshTokenOnce()
      return await http.request(response.config)
    } catch {
      clearToken()
      window.location.href = '/login'
    }
  }
  const businessError = new Error(message)
  businessError.code = code
  // 额度超限（429）：后端返回字符串业务码 QUOTA_EXCEEDED 与剩余额度
  if (body?.remainingQuota != null) {
    businessError.remainingQuota = body.remainingQuota
  }
  throw businessError
}

function refreshTokenOnce() {
  if (!refreshing) {
    refreshing = axios
      .post('/api/auth/refresh', null, { timeout: 10000 })
      .then((response) => {
        const data = response.data?.data
        if (response.data?.code !== 0 || !data?.token) {
          throw new Error('refresh failed')
        }
        setToken(data.token)
      })
      .finally(() => {
        setTimeout(() => {
          refreshing = null
        }, 0)
      })
  }
  return refreshing
}

// ---------- 认证 ----------
export const authApi = {
  register: (username, password) => http.post('/auth/register', { username, password }),
  login: (username, password) => http.post('/auth/login', { username, password }),
  // 邮箱验证码登录：发码（60 秒防刷）→ 验证码登录（邮箱不存在自动注册）
  sendCode: (email) => http.post('/auth/send-code', { email }),
  loginByCode: (email, code) => http.post('/auth/login-by-code', { email, code }),
  logout: () => http.post('/auth/logout', null),
  me: () => http.get('/auth/me')
}

// ---------- 知识库 / 问答 ----------
export const knowledgeApi = {
  importBuiltin: () => http.post('/knowledge/import', null),
  categories: () => http.get('/knowledge/categories'),
  mine: () => http.get('/knowledge/mine'),
  official: () => http.get('/knowledge/official'),
  recommend: (resumeId = null) => http.get('/knowledge/recommend', { params: resumeId == null ? {} : { resumeId } }),
  // 上传资料：FormData（file + 可选 category），仅支持 .md/.txt，单文件 ≤1MB
  upload: (formData) => http.post('/knowledge/upload', formData, { headers: { 'Content-Type': 'multipart/form-data' } }),
  remove: (id) => http.delete(`/knowledge/${id}`),
  // 迁移本人资料到指定分组：分组名可为已有标签或新建标签
  updateCategory: (id, category) => http.put(`/knowledge/${id}/category`, { category }),
  // 批量删除本人资料：后端仅删归属本人的 id，返回实际删除条数
  batchRemove: (ids) => http.post('/knowledge/batch-delete', { ids }),
  // 批量迁移本人资料到指定标签：标签名可为已有或新建，返回实际迁移条数
  batchMove: (ids, category) => http.post('/knowledge/batch-move', { ids, category })
}

export const qaApi = {
  ask: (question) => http.post('/qa/ask', { question }),
  // 流式提问：message/done/error 事件契约与面试/训练一致；history 为最近若干轮对话（追问上下文）
  askStream: (question, history, callbacks) => sseRequest('/api/qa/ask-stream', { question, history }, callbacks)
}

// ---------- 面试 ----------
export const interviewApi = {
  // categories：勾选的资料分组（可空）；非空时出题仅用这些分组
  // includeAlgorithm：开启后 DEEP 阶段掺入算法手写编程题（任务 12）
  // 助手语气风格固定为后端缺省值 friendly（和蔼可亲），不再提供选择
  start: (position, resumeId = null, mode = null, categories = null, includeAlgorithm = null) =>
    http.post('/interview/start', { position, resumeId, mode, categories, includeAlgorithm }),
  status: (sessionId) => http.get(`/interview/${sessionId}/status`),
  // 暂存续考（任务 4）：取未结束的面试会话，无则返回 null
  activeSession: () => http.get('/interview/active-session'),
  finish: (sessionId) => http.post(`/interview/${sessionId}/finish`, null),
  // 岗位设置：当前选中岗位 + 自定义岗位清单（持久保存直到用户更改）
  positionSetting: () => http.get('/interview/position-setting'),
  savePositionSetting: (payload) => http.put('/interview/position-setting', payload)
}

// ---------- 简历 ----------
export const resumeApi = {
  save: (resume) => http.post('/resume', resume),
  parse: (rawText) => http.post('/resume/parse', { rawText }),
  list: () => http.get('/resume/list'),
  detail: (resumeId) => http.get(`/resume/detail/${resumeId}`),
  remove: (id) => http.delete(`/resume/${id}`)
}

/**
 * SSE 通用请求：POST + text/event-stream，逐块回调 onMessage，
 * done 事件（评分/动作/进度）回调 onDone，error 事件回调 onError。
 * 非 2xx 响应按状态码分类：429 限流、503 AI 服务不可用；401 静默续期后重连一次。
 * error 事件帧携带 40100（token 失效，SSE 恒返 200 不走 HTTP 401）时同样静默续期并重连一次。
 * 闲置保护：连接建立后若超时窗口内无任何数据到达（代理/网络悬挂），主动中止并报可重试超时错误。
 */
const SSE_IDLE_TIMEOUT_MS = 90000

async function sseRequest(url, body, callbacks, retried = false) {
  const controller = new AbortController()
  let idleTimer = null
  let idleTimedOut = false
  const armIdleTimer = () => {
    clearTimeout(idleTimer)
    idleTimer = setTimeout(() => {
      idleTimedOut = true
      controller.abort()
    }, SSE_IDLE_TIMEOUT_MS)
  }
  const disarmIdleTimer = () => clearTimeout(idleTimer)
  const idleTimeoutError = () => {
    const error = new Error('AI 响应超时，请重试')
    error.code = 50300
    return error
  }

  let response
  try {
    armIdleTimer()
    response = await fetch(url, {
      method: 'POST',
      headers: {
        ...(body ? { 'Content-Type': 'application/json' } : {}),
        Accept: 'text/event-stream',
        Authorization: `Bearer ${getToken()}`
      },
      body: body ? JSON.stringify(body) : null,
      signal: controller.signal
    })
  } catch {
    disarmIdleTimer()
    if (idleTimedOut) {
      throw idleTimeoutError()
    }
    const networkError = new Error('网络连接失败，请检查网络后重试')
    networkError.isNetwork = true
    throw networkError
  }
  if (response.status === 401 && !retried) {
    disarmIdleTimer()
    try {
      await refreshTokenOnce()
      return await sseRequest(url, body, callbacks, true)
    } catch {
      clearToken()
      window.location.href = '/login'
    }
  }
  if (!response.ok || !response.body) {
    disarmIdleTimer()
    throw await classifySseStatus(response)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  try {
    for (;;) {
      armIdleTimer()
      const { done, value } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true })
      let separator
      while ((separator = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, separator)
        buffer = buffer.slice(separator + 2)
        // 鉴权失效错误帧：续期后重连一次（鉴权在发送任何对话内容之前，重放无副作用）
        if (dispatchSseFrame(frame, callbacks, retried) === 'authRetry') {
          disarmIdleTimer()
          reader.cancel().catch(() => {})
          try {
            await refreshTokenOnce()
          } catch {
            clearToken()
            window.location.href = '/login'
            return
          }
          return await sseRequest(url, body, callbacks, true)
        }
      }
    }
  } catch (e) {
    if (idleTimedOut) {
      throw idleTimeoutError()
    }
    const networkError = new Error('网络连接失败，请检查网络后重试')
    networkError.isNetwork = true
    throw networkError
  } finally {
    disarmIdleTimer()
  }
}

async function classifySseStatus(response) {
  const status = response.status
  // 429 读取 body 分类：额度超限返回字符串业务码 QUOTA_EXCEEDED，其余按限流提示
  if (status === 429) {
    try {
      const body = await response.json()
      if (body?.code === 'QUOTA_EXCEEDED') {
        const error = new Error(body.message || '今日免费额度已用完')
        error.code = 'QUOTA_EXCEEDED'
        error.remainingQuota = body.remainingQuota ?? 0
        return error
      }
    } catch {
      // body 非 JSON 时按普通限流处理
    }
    const error = new Error('请求过于频繁，请稍后再试')
    error.code = 42900
    return error
  }
  let code = -1
  let message = '面试对话连接失败，请稍后重试'
  if (status === 503) {
    code = 50300
    message = 'AI 响应超时，请重试'
  }
  const error = new Error(message)
  error.code = code
  return error
}

export function askStream(sessionId, message, callbacks) {
  return sseRequest(`/api/interview/${sessionId}/ask`, { message }, callbacks)
}

// ---------- 专项训练（任务 7）：SSE 契约与面试 ask 一致 ----------
export const trainingApi = {
  // fromInterview：面试「深入该模块」跳转豁免互斥；助手风格固定后端缺省 friendly
  start: (category, fromInterview = false) => http.post('/training/start', { category, fromInterview }),
  status: (sessionId) => http.get(`/training/${sessionId}/status`),
  finish: (sessionId) => http.post(`/training/${sessionId}/finish`, null),
  // 训练历史分页（按完成时间倒序）：返回 Page 结构（content/totalElements/totalPages）
  records: (page = 0, size = 20) => http.get('/training/records', { params: { page, size } }),
  // 训练报告详情：概要 + 逐题明细（查看/打印报告页数据源）
  recordReport: (id) => http.get(`/training/records/${id}/report`)
}

// 专项训练作答：message/segment/progress/done/error 事件结构与面试一致
export function trainingAnswerStream(sessionId, message, callbacks) {
  return sseRequest(`/api/training/${sessionId}/answer`, { message }, callbacks)
}

// 专项训练「已掌握」：绿勾标记后出下一题，不计分不入历史，SSE 契约与 answer 一致
export function trainingMasteredStream(sessionId, callbacks) {
  return sseRequest(`/api/training/${sessionId}/mastered`, null, callbacks)
}

// 专项训练「不知道」：等价作答「不知道」走完整评估反馈（强制 0 分）+ 红叉，SSE 契约与 answer 一致
export function trainingDontknowStream(sessionId, callbacks) {
  return sseRequest(`/api/training/${sessionId}/dontknow`, null, callbacks)
}

// 面试「已掌握」（仅训练模式）：绿勾标记后出下一题，不计分不入历史，SSE 契约与 ask 一致
export function masteredStream(sessionId, callbacks) {
  return sseRequest(`/api/interview/${sessionId}/mastered`, null, callbacks)
}

// 面试「不知道」（仅训练模式）：等价作答「不知道」走完整评估反馈（强制 0 分）+ 红叉，SSE 契约与 ask 一致
export function dontknowStream(sessionId, callbacks) {
  return sseRequest(`/api/interview/${sessionId}/dontknow`, null, callbacks)
}

// 训练模式“深度训练”：丢弃暂存追问进入深度训练子流程，SSE 契约与 ask 一致
export function deepTrainingStream(sessionId, callbacks) {
  return sseRequest(`/api/interview/${sessionId}/deep-training`, null, callbacks)
}

// 退出深度训练：恢复主面试并出下一题，SSE 契约与 ask 一致
export function deepTrainingExitStream(sessionId, callbacks) {
  return sseRequest(`/api/interview/${sessionId}/deep-training/exit`, null, callbacks)
}

// 训练模式“下一板块”：放弃深度训练机会并推进到下一题/下一阶段，SSE 契约与 ask 一致
export function nextQuestionStream(sessionId, callbacks) {
  return sseRequest(`/api/interview/${sessionId}/next-question`, null, callbacks)
}

// 返回 'authRetry' 表示错误帧为鉴权失效，由调用方续期重连而非报错
function dispatchSseFrame(frame, { onMessage, onDone, onError, onSegment, onProgress }, retried) {
  let eventName = 'message'
  const dataLines = []
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
    }
  }
  // segment/progress 帧允许空载荷（segment 仅作分段信号）；done/error 无载荷时忽略防解析异常
  const payload = dataLines.join('\n')
  if (!payload && eventName !== 'segment' && eventName !== 'progress') {
    return ''
  }
  if (eventName === 'message') {
    if (payload) {
      onMessage?.(payload)
    }
  } else if (eventName === 'segment') {
    onSegment?.()
  } else if (eventName === 'progress') {
    onProgress?.(payload)
  } else if (eventName === 'done') {
    onDone?.(JSON.parse(payload))
  } else if (eventName === 'error') {
    const parsed = JSON.parse(payload)
    if (parsed.code === 40100 && !retried) {
      return 'authRetry'
    }
    const error = new Error(parsed.message || '面试对话异常')
    error.code = parsed.code
    onError?.(error)
  }
  return ''
}

// ---------- API Key / 额度 ----------
export const apiKeyApi = {
  get: () => http.get('/apikey'),
  save: (payload) => http.post('/apikey', payload),
  remove: () => http.delete('/apikey')
}

export const quotaApi = {
  get: () => http.get('/quota')
}

// ---------- 报告 ----------
export const reportApi = {
  get: (interviewId) => http.get(`/report/${interviewId}`),
  // mode：training/practice 按模式过滤；缺省返回全部
  history: (page = 0, size = 10, mode = null) =>
    http.get('/report/history', { params: mode ? { page, size, mode } : { page, size } }),
  progress: (limit = 10) => http.get('/report/progress', { params: { limit } })
}

// ---------- 管理台 ----------
export const adminApi = {
  // whoami 仅需登录：返回 { admin } 供顶栏判断是否展示管理入口；未登录报错由调用方忽略
  whoami: () => http.get('/admin/whoami'),
  stats: () => http.get('/admin/stats'),
  // 用户分页：page 从 1 开始；keyword 按用户名/昵称/邮箱模糊匹配
  users: (params) => http.get('/admin/users', { params }),
  ban: (id) => http.post(`/admin/users/${id}/ban`, null),
  unban: (id) => http.post(`/admin/users/${id}/unban`, null)
}
