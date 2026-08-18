// 专项训练会话全局 store：对话消息与进行中的 SSE 请求保存在模块级单例中，
// 切换标签页（组件卸载）不中断流式回合，回到页面即可看到完整的评价与回复。
// 刷新页面时消息不回放，仅凭后端 status 恢复当前题目（既有约定）。
import { reactive } from 'vue'
import { trainingApi, trainingAnswerStream } from '../api'
import { classifyError } from '../utils/errors'
import { toast } from '../toast'

export const TRAINING_SESSION_KEY = 'offerforge_training_session'

export const trainingSession = reactive({
  sessionId: '',
  status: null,
  messages: [],
  sending: false,
  thinkingText: '',
  error: ''
})

/** 开始一场训练：成功后会话挂到模块级，后续切标签不影响 */
export async function startTrainingSession(category, style) {
  const data = await trainingApi.start(category, style)
  trainingSession.sessionId = data.sessionId
  trainingSession.status = data.status
  trainingSession.messages = [{ role: 'assistant', content: data.openingMessage }]
  trainingSession.error = ''
  try {
    sessionStorage.setItem(TRAINING_SESSION_KEY, data.sessionId)
  } catch {
    // 存储不可用不影响当前会话
  }
  return data
}

/**
 * 提交作答：SSE 流式渲染导师点评与下一题，done 帧携带评分与详细评估。
 * 请求挂在模块级，切换标签页后台继续接收，回来后视图自动补齐。
 */
export function submitTrainingAnswer(text) {
  const trimmed = (text || '').trim()
  if (!trimmed || trainingSession.sending || !trainingSession.sessionId || trainingSession.status?.finished) {
    return
  }
  trainingSession.sending = true
  trainingSession.error = ''
  trainingSession.messages.push({ role: 'user', content: trimmed })
  const assistantMessage = { role: 'assistant', content: '' }
  trainingSession.messages.push(assistantMessage)
  // 取数组代理对象而非原始对象，保证后续属性写入能触发视图更新
  const firstBubble = trainingSession.messages[trainingSession.messages.length - 1]
  // 当前承接流式内容的气泡：segment 帧后重指向新气泡
  let currentBubble = firstBubble
  trainingSession.thinkingText = '正在评估你的回答…'
  trainingAnswerStream(trainingSession.sessionId, trimmed, {
    onMessage: (chunk) => {
      trainingSession.thinkingText = ''
      currentBubble.content += chunk
    },
    onSegment: () => {
      trainingSession.messages.push({ role: 'assistant', content: '' })
      currentBubble = trainingSession.messages[trainingSession.messages.length - 1]
    },
    onProgress: (progressText) => {
      trainingSession.thinkingText = progressText
    },
    onDone: (result) => {
      attachScore(result)
      trainingSession.status = result.status
      trainingSession.sending = false
      trainingSession.thinkingText = ''
    },
    onError: (e) => failStream(firstBubble, e)
  }).catch((e) => {
    failStream(firstBubble, e)
  }).finally(() => {
    if (trainingSession.sending && !trainingSession.thinkingText) {
      // 连接结束但无 done 事件：释放输入状态
      trainingSession.sending = false
    }
  })
}

function attachScore(result) {
  // 得分徽章挂在最近一个有内容的气泡上（导师点评气泡），附带详细评估供「具体分析」展开
  for (let i = trainingSession.messages.length - 1; i >= 0; i--) {
    const item = trainingSession.messages[i]
    if (item.role === 'assistant' && item.content && item.score == null) {
      item.score = result.score
      item.evaluation = result.evaluation || null
      break
    }
  }
}

function failStream(assistantMessage, e) {
  trainingSession.sending = false
  trainingSession.thinkingText = ''
  const classified = classifyError(e)
  if (assistantMessage.content) {
    assistantMessage.comment = '连接已中断，请刷新页面查看最新进度'
  } else {
    trainingSession.messages.pop()
  }
  trainingSession.error = classified.message
  toast.error(classified.message)
}

/**
 * 页面加载时恢复会话：模块级已有活跃会话（切标签回来）直接沿用；
 * 否则按 sessionStorage 里的会话 id 向后端查状态恢复当前题目。
 * 返回 'active' | ''，'' 表示无可恢复会话。
 */
export async function restoreTrainingSession() {
  if (trainingSession.sessionId && !trainingSession.status?.finished) {
    return 'active'
  }
  let saved = null
  try {
    saved = sessionStorage.getItem(TRAINING_SESSION_KEY)
  } catch {
    return ''
  }
  if (!saved) {
    return ''
  }
  try {
    const restored = await trainingApi.status(saved)
    if (restored?.finished) {
      clearTrainingSession()
      return ''
    }
    trainingSession.sessionId = saved
    trainingSession.status = restored
    trainingSession.messages = [{
      role: 'assistant',
      content: restored.currentQuestion || '欢迎回来，请继续作答当前题目。',
      restored: true
    }]
    return 'active'
  } catch {
    clearTrainingSession()
    return ''
  }
}

/** 清空模块级会话与存储（回到选择页 / 训练归档后） */
export function clearTrainingSession() {
  trainingSession.sessionId = ''
  trainingSession.status = null
  trainingSession.messages = []
  trainingSession.sending = false
  trainingSession.thinkingText = ''
  trainingSession.error = ''
  try {
    sessionStorage.removeItem(TRAINING_SESSION_KEY)
  } catch {
    // ignore
  }
}
