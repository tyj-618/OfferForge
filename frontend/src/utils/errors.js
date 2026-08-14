import { toast } from '../toast'

/**
 * 错误分类：把网络异常与后端错误码映射为面向用户的文案。
 * - 网络错误：连接失败，可重试
 * - 50300：LLM 超时 / AI 服务不可用，可重试
 * - 50301 / 50000：服务端异常，不暴露技术细节，不建议立即重试
 * - 42900：触发限流
 * - 其余业务错误（40000/40900 等）：直接展示后端 message
 */
export function classifyError(error) {
  if (error?.isNetwork) {
    return { message: '网络连接失败，请检查网络后重试', retryable: true }
  }
  switch (error?.code) {
    case 50300:
      return { message: 'AI 响应超时，请重试', retryable: true }
    case 50301:
    case 50000:
      return { message: '服务暂时不可用，请稍后再试', retryable: false }
    case 42900:
      return { message: '请求过于频繁，请稍后再试', retryable: false }
    default:
      return { message: error?.message || '请求失败', retryable: true }
  }
}

/** 统一错误 Toast：可重试错误附带「重试」按钮，返回分类信息供调用方二次使用 */
export function notifyError(error, retryFn) {
  const info = classifyError(error)
  toast.error(info.message, info.retryable && retryFn ? retryFn : null)
  return info
}
