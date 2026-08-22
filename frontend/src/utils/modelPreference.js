// 官方模型偏好：设置页「官方模型选择」写入，面试/训练开局读取作为 model 参数；
// 空串表示系统默认模型。纯客户端偏好（非敏感数据），自带 Key 用户由后端忽略该选择。
const PREFERRED_MODEL_KEY = 'offerforge.preferredOfficialModel'

export function getPreferredModel() {
  try {
    return localStorage.getItem(PREFERRED_MODEL_KEY) || ''
  } catch {
    return ''
  }
}

export function setPreferredModel(modelId) {
  try {
    localStorage.setItem(PREFERRED_MODEL_KEY, modelId || '')
  } catch {
    // 隐私模式等写入失败：仅影响跨会话持久化，当前页选择仍生效
  }
}
