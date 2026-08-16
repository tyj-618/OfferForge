import axios from 'axios'

const TOKEN_KEY = 'offerforge_token'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token || '')
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
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
  logout: () => http.post('/auth/logout', null)
}

// ---------- 知识库 / 问答 ----------
export const knowledgeApi = {
  importBuiltin: () => http.post('/knowledge/import', null)
}

export const qaApi = {
  ask: (question) => http.post('/qa/ask', { question })
}

// ---------- 面试 ----------
export const interviewApi = {
  start: (position, resumeId = null) => http.post('/interview/start', { position, resumeId }),
  status: (sessionId) => http.get(`/interview/${sessionId}/status`),
  finish: (sessionId) => http.post(`/interview/${sessionId}/finish`, null)
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
 */
async function sseRequest(url, body, callbacks, retried = false) {
  let response
  try {
    response = await fetch(url, {
      method: 'POST',
      headers: {
        ...(body ? { 'Content-Type': 'application/json' } : {}),
        Accept: 'text/event-stream',
        Authorization: `Bearer ${getToken()}`
      },
      body: body ? JSON.stringify(body) : null
    })
  } catch {
    const networkError = new Error('网络连接失败，请检查网络后重试')
    networkError.isNetwork = true
    throw networkError
  }
  if (response.status === 401 && !retried) {
    try {
      await refreshTokenOnce()
      return await sseRequest(url, body, callbacks, true)
    } catch {
      clearToken()
      window.location.href = '/login'
    }
  }
  if (!response.ok || !response.body) {
    throw await classifySseStatus(response)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) {
      break
    }
    buffer += decoder.decode(value, { stream: true })
    let separator
    while ((separator = buffer.indexOf('\n\n')) >= 0) {
      const frame = buffer.slice(0, separator)
      buffer = buffer.slice(separator + 2)
      dispatchSseFrame(frame, callbacks)
    }
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

// 跳过当前题：无请求体，SSE 契约与 ask 一致（计 0 分后推进状态机）
export function skipStream(sessionId, callbacks) {
  return sseRequest(`/api/interview/${sessionId}/skip`, null, callbacks)
}

function dispatchSseFrame(frame, { onMessage, onDone, onError }) {
  let eventName = 'message'
  const dataLines = []
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
    }
  }
  if (dataLines.length === 0) {
    return
  }
  const payload = dataLines.join('\n')
  if (eventName === 'message') {
    onMessage?.(payload)
  } else if (eventName === 'done') {
    onDone?.(JSON.parse(payload))
  } else if (eventName === 'error') {
    const parsed = JSON.parse(payload)
    const error = new Error(parsed.message || '面试对话异常')
    error.code = parsed.code
    onError?.(error)
  }
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
  history: (page = 0, size = 10) => http.get('/report/history', { params: { page, size } }),
  progress: (limit = 10) => http.get('/report/progress', { params: { limit } })
}
