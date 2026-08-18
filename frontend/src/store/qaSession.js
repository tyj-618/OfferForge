// 快捷提问会话全局 store：对话与进行中的流式请求保存在模块级单例中，
// 组件卸载（切换标签页）不会中断请求，回到页面即可看到继续输出的回答。
// 持久化策略（用户约定）：仅当前登录期间临时保存于 sessionStorage，
// 主动清空会话或退出登录即清除；保存超过 24 小时自动作废。
import { reactive } from 'vue'
import { qaApi } from '../api'
import { toast } from '../toast'

export const QA_STORAGE_KEY = 'offerforge_qa_session'
/** 对话消息条数上限：达到后提示用户清空会话再继续提问 */
export const QA_MAX_MESSAGES = 100
/** 随请求携带的历史消息条数（追问上下文窗口） */
const HISTORY_WINDOW = 10
const TTL_MS = 24 * 60 * 60 * 1000

export const qaSession = reactive({
  conversations: [],
  asking: false
})

/** 进入页面时恢复本登录期内的历史对话（超过 24 小时自动清除） */
export function restoreQaSession() {
  if (qaSession.conversations.length > 0) {
    return
  }
  let raw = null
  try {
    raw = sessionStorage.getItem(QA_STORAGE_KEY)
  } catch {
    return
  }
  if (!raw) {
    return
  }
  try {
    const parsed = JSON.parse(raw)
    if (!parsed?.savedAt || Date.now() - parsed.savedAt > TTL_MS) {
      sessionStorage.removeItem(QA_STORAGE_KEY)
      return
    }
    if (Array.isArray(parsed.conversations)) {
      qaSession.conversations = parsed.conversations
        .filter((item) => item && (item.role === 'user' || item.role === 'assistant') && typeof item.content === 'string')
        .map((item) => ({ role: item.role, content: item.content, refs: Array.isArray(item.refs) ? item.refs : [] }))
    }
  } catch {
    sessionStorage.removeItem(QA_STORAGE_KEY)
  }
}

function persist() {
  try {
    sessionStorage.setItem(QA_STORAGE_KEY, JSON.stringify({
      savedAt: Date.now(),
      conversations: qaSession.conversations
    }))
  } catch {
    // 存储不可用（隐私模式/超额）时仅影响历史保留，不影响当前对话
  }
}

export function isOverLimit() {
  return qaSession.conversations.length >= QA_MAX_MESSAGES
}

/** 清空会话：本地与存储一并清除（进行中的提问不受影响，其气泡仍会正常收尾） */
export function clearQaSession() {
  qaSession.conversations = []
  try {
    sessionStorage.removeItem(QA_STORAGE_KEY)
  } catch {
    // ignore
  }
}

/**
 * 发起流式提问：请求挂在模块级，切换标签页后台继续执行。
 * 达到长度上限时拒绝并提示用户清空会话。
 */
export function sendQaQuestion(text) {
  const question = (text || '').trim()
  if (!question || qaSession.asking) {
    return
  }
  if (isOverLimit()) {
    toast.error('对话已达长度上限，请点击「清空会话」删除历史后再提问')
    return
  }
  const history = qaSession.conversations
    .filter((item) => (item.role === 'user' && item.content) || (item.role === 'assistant' && item.content && !item.error))
    .slice(-HISTORY_WINDOW)
    .map((item) => ({ role: item.role, content: item.content }))
  qaSession.conversations.push({ role: 'user', content: question })
  qaSession.conversations.push({ role: 'assistant', content: '', refs: [], streaming: true, error: '' })
  // 取数组代理对象而非原始对象，保证后续属性写入能触发视图更新
  const bubble = qaSession.conversations[qaSession.conversations.length - 1]
  qaSession.asking = true
  persist()
  qaApi.askStream(question, history, {
    onMessage: (chunk) => {
      bubble.content += chunk
    },
    onDone: (payload) => {
      bubble.refs = payload?.referencedKnowledgeIds || []
      bubble.streaming = false
      qaSession.asking = false
      persist()
    },
    onError: (error) => {
      failBubble(bubble, error)
    }
  }).catch((error) => {
    failBubble(bubble, error)
  })
}

function failBubble(bubble, error) {
  if (bubble.streaming) {
    bubble.streaming = false
    bubble.content = ''
    bubble.error = error?.message || '回答失败，请重试'
  }
  qaSession.asking = false
  persist()
}

/** 重试失败的回答：移除错误气泡，沿用其前的用户提问重新发起 */
export function retryQaAt(index) {
  const item = qaSession.conversations[index]
  if (!item?.error) {
    return
  }
  const userMessage = qaSession.conversations[index - 1]
  qaSession.conversations.splice(index, 1)
  persist()
  if (userMessage?.role === 'user' && userMessage.content) {
    sendQaQuestion(userMessage.content)
  }
}
