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
      throw new Error('网络异常，请检查后端服务是否启动')
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
  start: (position) => http.post('/interview/start', { position }),
  status: (sessionId) => http.get(`/interview/${sessionId}/status`),
  finish: (sessionId) => http.post(`/interview/${sessionId}/finish`, null)
}

/**
 * SSE 问答：POST + text/event-stream，逐块回调 onMessage，
 * done 事件（评分/动作/进度）回调 onDone，error 事件回调 onError。
 */
export async function askStream(sessionId, message, { onMessage, onDone, onError }) {
  const response = await fetch(`/api/interview/${sessionId}/ask`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      Authorization: `Bearer ${getToken()}`
    },
    body: JSON.stringify({ message })
  })
  if (!response.ok || !response.body) {
    throw new Error('面试对话连接失败，请稍后重试')
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
      dispatchSseFrame(frame, { onMessage, onDone, onError })
    }
  }
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
    const error = JSON.parse(payload)
    onError?.(new Error(error.message || '面试对话异常'))
  }
}

// ---------- 报告 ----------
export const reportApi = {
  get: (interviewId) => http.get(`/report/${interviewId}`),
  history: (page = 0, size = 10) => http.get('/report/history', { params: { page, size } }),
  progress: (limit = 10) => http.get('/report/progress', { params: { limit } })
}
