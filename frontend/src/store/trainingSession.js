// 专项训练会话全局 store：对话消息与进行中的 SSE 请求保存在模块级单例中，
// 切换标签页（组件卸载）不中断流式回合，回到页面即可看到完整的评价与回复。
// 刷新页面时按后端 status.history 完整回放历史对话；若刷新时有回合在服务端进行中，
// 轮询至回合完成后重建，实现「刷新后继续未完成的对话」。
import { reactive } from 'vue'
import { trainingApi, trainingAnswerStream, trainingMasteredStream, trainingDontknowStream } from '../api'
import { classifyError } from '../utils/errors'
import { toast } from '../toast'

export const TRAINING_SESSION_KEY = 'offerforge_training_session'

export const trainingSession = reactive({
  sessionId: '',
  status: null,
  messages: [],
  sending: false,
  thinkingText: '',
  error: '',
  // 计费场次余额耗尽标识：视图据此呈现充值引导横幅（与 error 文案配套）
  insufficientBalance: false
})

/** 开始一场训练：成功后会话挂到模块级，后续切标签不影响；fromInterview：面试深入跳转豁免互斥；助手风格固定后端缺省 friendly；model：付费模型选择（可空） */
export async function startTrainingSession(category, fromInterview = false, model = null) {
  const data = await trainingApi.start(category, fromInterview, model)
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
  runTurn((callbacks) => trainingAnswerStream(trainingSession.sessionId, trimmed, callbacks), trimmed, '正在评估你的回答…')
}

/** 「已掌握」：绿勾标记后本题直接 pass（不计分不入历史），SSE 返回下一题教练话术 */
export function submitTrainingMastered() {
  if (trainingSession.sending || !trainingSession.sessionId || trainingSession.status?.finished) {
    return
  }
  runTurn((callbacks) => trainingMasteredStream(trainingSession.sessionId, callbacks), '✓ 已掌握，继续下一题', '正在准备下一题…')
}

/** 「不知道」：等价作答「不知道」走完整评估反馈（强制 0 分）+ 红叉 */
export function submitTrainingDontknow() {
  if (trainingSession.sending || !trainingSession.sessionId || trainingSession.status?.finished) {
    return
  }
  runTurn((callbacks) => trainingDontknowStream(trainingSession.sessionId, callbacks), '不知道', '正在评估你的回答…')
}

/** SSE 回合公共骨架：推用户气泡 + 占位助手气泡，逐帧渲染，done 帧结算评分与进度 */
function runTurn(request, userText, initialThinkingText) {
  trainingSession.sending = true
  trainingSession.error = ''
  // 新回合发起即清除余额不足标识（充值后回来继续作答的场景）
  trainingSession.insufficientBalance = false
  trainingSession.messages.push({ role: 'user', content: userText })
  const assistantMessage = { role: 'assistant', content: '' }
  trainingSession.messages.push(assistantMessage)
  // 取数组代理对象而非原始对象，保证后续属性写入能触发视图更新
  const firstBubble = trainingSession.messages[trainingSession.messages.length - 1]
  // 当前承接流式内容的气泡：segment 帧后重指向新气泡
  let currentBubble = firstBubble
  trainingSession.thinkingText = initialThinkingText
  request({
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
      attachScore(firstBubble, result)
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

function attachScore(commentBubble, result) {
  if (result.score == null) {
    // 「已掌握」回合无评分：在用户标记气泡上挂绿勾标记，不展示得分徽章
    for (let i = trainingSession.messages.length - 1; i >= 0; i--) {
      const item = trainingSession.messages[i]
      if (item.role === 'user') {
        item.masteredMark = true
        break
      }
    }
    return
  }
  // 得分徽章挂在本回合的导师点评气泡（回合开始的第一个助手气泡）上，
  // 保证「得分 + 具体分析」展示在下一题气泡之前；不能倒序找最近气泡（那是下一题）
  if (commentBubble && commentBubble.role === 'assistant') {
    commentBubble.score = result.score
    commentBubble.evaluation = result.evaluation || null
  }
}

function failStream(assistantMessage, e) {
  trainingSession.sending = false
  trainingSession.thinkingText = ''
  if (e?.code === 'INSUFFICIENT_BALANCE') {
    // 计费场次余额耗尽：标记供视图呈现充值引导（非连接异常，不提供重试）
    trainingSession.insufficientBalance = true
  }
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
 * 否则按 sessionStorage 里的会话 id 向后端查状态，用 history 完整重建历史对话。
 * 若服务端正在评估上次作答（刷新时回合未完成），轮询至完成后重建续接。
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
    trainingSession.messages = buildMessagesFromStatus(restored)
    if (restored?.evaluating) {
      waitForEvaluation(saved)
    }
    return 'active'
  } catch {
    clearTrainingSession()
    return ''
  }
}

/**
 * 按后端状态重建完整对话：每个已作答回合还原为 题目气泡 → 用户回答气泡 →
 * 导师点评气泡（携带得分与详细评估），末尾追加当前待作答题目。
 */
function buildMessagesFromStatus(status) {
  const rebuilt = []
  for (const item of status?.history || []) {
    if (item.question) {
      rebuilt.push({ role: 'assistant', content: item.question })
    }
    if (item.answer) {
      rebuilt.push({ role: 'user', content: item.answer })
    }
    if (item.comment) {
      rebuilt.push({
        role: 'assistant',
        content: item.comment,
        score: item.score,
        evaluation: item.evaluation || null
      })
    }
  }
  if (!status?.finished && status?.currentQuestion) {
    rebuilt.push({ role: 'assistant', content: status.currentQuestion, restored: true })
  }
  return rebuilt
}

/**
 * 刷新时上一回合仍在服务端进行中：轮询 status 至 evaluating 结束，
 * 再用最新 history 重建对话（含本回合的评价与下一题），实现续接。
 */
function waitForEvaluation(sessionId) {
  trainingSession.sending = true
  trainingSession.thinkingText = '正在续接你上次提交的回答，评估中…'
  let attempts = 0
  const MAX_ATTEMPTS = 72 // 2.5s × 72 ≈ 3 分钟，覆盖回合最坏耗时
  const timer = setInterval(async () => {
    attempts += 1
    try {
      const latest = await trainingApi.status(sessionId)
      if (!latest?.evaluating || latest?.finished || attempts >= MAX_ATTEMPTS) {
        clearInterval(timer)
        trainingSession.status = latest
        trainingSession.messages = buildMessagesFromStatus(latest)
        trainingSession.sending = false
        trainingSession.thinkingText = ''
      }
    } catch {
      // 偶发网络抖动继续重试；连续失败超限则释放输入，保留已重建的历史
      if (attempts >= MAX_ATTEMPTS) {
        clearInterval(timer)
        trainingSession.sending = false
        trainingSession.thinkingText = ''
      }
    }
  }, 2500)
}

/** 清空模块级会话与存储（回到选择页 / 训练归档后） */
export function clearTrainingSession() {
  trainingSession.sessionId = ''
  trainingSession.status = null
  trainingSession.messages = []
  trainingSession.sending = false
  trainingSession.thinkingText = ''
  trainingSession.error = ''
  trainingSession.insufficientBalance = false
  try {
    sessionStorage.removeItem(TRAINING_SESSION_KEY)
  } catch {
    // ignore
  }
}
