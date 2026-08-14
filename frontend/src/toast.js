import { reactive } from 'vue'

// 全局 Toast 队列：各视图共享，默认 3 秒后自动消失
export const toasts = reactive([])
let seq = 0

function push({ type, message, action = null }) {
  const id = ++seq
  toasts.push({ id, type, message, action })
  setTimeout(() => dismiss(id), 3000)
}

export function dismiss(id) {
  const index = toasts.findIndex((item) => item.id === id)
  if (index >= 0) {
    toasts.splice(index, 1)
  }
}

export const toast = {
  success: (message) => push({ type: 'success', message }),
  info: (message) => push({ type: 'info', message }),
  // retryFn 存在时 Toast 内展示「重试」按钮
  error: (message, retryFn = null) =>
    push({ type: 'error', message, action: retryFn ? { label: '重试', handler: retryFn } : null })
}
