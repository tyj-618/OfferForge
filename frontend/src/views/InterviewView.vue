<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { marked } from 'marked'
import {
  askStream,
  billingApi,
  billingState,
  deepTrainingExitStream,
  dontknowStream,
  interviewApi,
  knowledgeApi,
  masteredStream,
  quotaApi,
  refreshBillingState,
  resumeApi
} from '../api'
import { classifyError, notifyError } from '../utils/errors'
import { isMobileViewport } from '../utils/device'
import { toast } from '../toast'

// 面试官话术含 Markdown（粗体/斜体/列表等），gfm + breaks；html 默认关闭，原始 HTML 会被转义
marked.use({ gfm: true, breaks: true })

function renderMarkdown(content) {
  if (!content) {
    return ''
  }
  return marked.parse(content)
}

const router = useRouter()
const SESSION_KEY = 'offerforge_session'
const MODE_KEY = 'offerforge_session_mode'
// 手机端不展示键盘快捷键提示，placeholder 简化
const answerPlaceholder = isMobileViewport()
  ? '输入你的回答…'
  : '输入你的回答…（Enter 发送，Shift+Enter 换行）'

const phase = ref('idle') // idle | mode-select | active | finishing
const position = ref('')
const sessionId = ref('')
const status = ref(null)
const messages = ref([])
const answer = ref('')
const sending = ref(false)
const thinking = ref(false)
// 思考中状态文案：随后端 progress 帧更新（评估/出题等阻塞节点的实时反馈）
const thinkingText = ref('')
const error = ref('')
const mode = ref('practice') // training | practice

// 训练模式「具体分析」小窗：同一时刻最多展开一个，锚定在对应得分徽章旁；
// 弹窗高度/上下方位按会话窗口可见区域动态计算，保证完全包含在会话窗口内
const analysisOpenIndex = ref(null)
const analysisPlacement = ref({ maxHeight: '340px', below: false })

function toggleAnalysis(index, event) {
  if (analysisOpenIndex.value === index) {
    analysisOpenIndex.value = null
    return
  }
  const box = chatBox.value
  const anchor = event?.currentTarget?.closest('.analysis-anchor')
  if (box && anchor) {
    const boxRect = box.getBoundingClientRect()
    const anchorRect = anchor.getBoundingClientRect()
    const spaceAbove = anchorRect.top - boxRect.top
    const spaceBelow = boxRect.bottom - anchorRect.bottom
    // 上方空间不足且下方更宽裕时翻转到下方；最大高度取可用空间减去边距
    const below = spaceAbove < 220 && spaceBelow > spaceAbove
    const space = (below ? spaceBelow : spaceAbove) - 16
    analysisPlacement.value = {
      maxHeight: Math.max(140, Math.min(420, space)) + 'px',
      below
    }
  }
  analysisOpenIndex.value = index
}

// 任务 4：暂存续考——开始卡片展示「继续未完成的面试」入口；
// 「深入该模块」跳转专项训练前二次确认（将暂存当前面试进度）
const resumableSession = ref(null)
const confirmDiscard = ref(false)
const confirmDeepDive = ref('')

// 深入目标分组需在可见资料分组内（项目经历等非题库分组不展示入口）
function isCategoryAvailable(category) {
  return categoryOptions.value.some((opt) => opt.name === category)
}

function requestDeepDive(category) {
  if (sending.value) {
    return
  }
  confirmDeepDive.value = category
}

function cancelDeepDive() {
  confirmDeepDive.value = ''
}

// 确认深入：面试进度由后端会话保持（24h TTL），跳转专项训练并带入目标分组
function goDeepDive() {
  const category = confirmDeepDive.value
  confirmDeepDive.value = ''
  router.push(`/training?category=${encodeURIComponent(category)}&from=interview`)
}

const resumes = ref([])
const selectedResumeId = ref(null)

// 出题范围（任务 8）：勾选的资料分组；不勾选按阶段默认官方题库
const categoryOptions = ref([])
const selectedCategories = ref([])
const recommendedCategories = ref([])

// 算法开关（任务 12）：开启后 DEEP 阶段按难度掺入算法分组题目
const includeAlgorithm = ref(false)

async function loadCategories() {
  try {
    const view = await knowledgeApi.categories()
    categoryOptions.value = [
      ...(view?.official || []).map((name) => ({ name, official: true })),
      ...(view?.custom || []).map((name) => ({ name, official: false }))
    ]
  } catch {
    // 分组加载失败不阻断开始面试（后端按默认题库出题）
  }
}

// 有简历时按简历技能关键词推荐分组（仅 ⭐ 展示；无持久化岗位时默认勾选）
async function loadRecommendedCategories() {
  if (!selectedResumeId.value) {
    return
  }
  try {
    recommendedCategories.value = (await knowledgeApi.recommend(selectedResumeId.value)) || []
  } catch {
    // 推荐失败静默降级：不展示推荐
  }
}

// 官方/自建标签分组展示，便于快捷勾选自己添加的标签
const officialOptions = computed(() => categoryOptions.value.filter((opt) => opt.official))
const customOptions = computed(() => categoryOptions.value.filter((opt) => !opt.official))

// 预设岗位：每个岗位预先绑定官方题库技术栈标签（选择后自动勾选，非关键词匹配），覆盖主流技术岗与 AI 新兴岗
const PRESET_POSITIONS = [
  {
    name: 'Java 后端工程师',
    tags: ['Java基础', 'Java集合', 'Java并发', 'JVM', 'Spring', 'Spring Boot', 'MySQL', 'Redis', '计算机网络', '设计模式']
  },
  {
    name: 'Java 开发实习生',
    tags: ['Java基础', 'Java集合', 'Spring', 'Spring Boot', 'MySQL', '计算机网络']
  },
  {
    name: 'Java 高级工程师',
    tags: ['Java并发', 'JVM', 'Spring', 'Spring Boot', 'MySQL', 'Redis', '设计模式', '计算机网络']
  },
  {
    name: 'Go 后端工程师',
    tags: ['Go语言基础', 'Go并发编程', 'MySQL', 'Redis', '计算机网络', '算法']
  },
  {
    name: 'Web 前端工程师',
    tags: ['JavaScript基础', 'CSS与布局', 'Vue', '浏览器与网络', '前端工程化', '算法']
  },
  {
    name: '前端高级工程师',
    tags: ['JavaScript基础', 'Vue', 'React', '浏览器与网络', '前端工程化', '算法']
  },
  {
    name: '测试开发工程师',
    tags: ['软件测试基础', '自动化与接口测试', '性能测试', '计算机网络', 'MySQL']
  },
  {
    name: '运维开发工程师（SRE）',
    tags: ['Linux与Shell', 'Docker与Kubernetes', '计算机网络', 'MySQL']
  },
  {
    name: 'AI 应用开发工程师',
    tags: ['大模型基础', 'Prompt工程', 'RAG应用', 'AI应用工程', '算法']
  },
  {
    name: 'Agent 开发工程师',
    tags: ['Agent开发', '大模型基础', 'Prompt工程', 'AI应用工程', 'RAG应用']
  },
  {
    name: '大模型算法工程师',
    tags: ['大模型基础', 'RAG应用', 'AI应用工程', '算法']
  }
]

// 岗位设置持久化：当前选中岗位 + 用户自定义岗位（后端保存，一直保持直到用户更改）
const customPositions = ref([])
const positionPickerOpen = ref(false)
const positionPickerRef = ref(null)

const currentPositionTags = computed(() => {
  const preset = PRESET_POSITIONS.find((p) => p.name === position.value)
  if (preset) {
    return preset.tags
  }
  const custom = customPositions.value.find((p) => p.name === position.value)
  return custom ? custom.tags : []
})

// 选中岗位：自动勾选其绑定的官方标签，同名自定义标签一并勾选（替换式，随后用户可手动微调）
function selectPosition(name) {
  position.value = name
  positionPickerOpen.value = false
  applyPositionTags(name)
  savePositionSetting()
}

function clearPosition() {
  position.value = ''
  positionPickerOpen.value = false
  savePositionSetting()
}

function applyPositionTags(name) {
  const preset = PRESET_POSITIONS.find((p) => p.name === name)
  const custom = customPositions.value.find((p) => p.name === name)
  const tags = preset ? preset.tags : (custom ? custom.tags : [])
  const known = new Set(categoryOptions.value.map((opt) => opt.name))
  selectedCategories.value = tags.filter((tag) => known.has(tag))
}

async function savePositionSetting() {
  try {
    await interviewApi.savePositionSetting({
      currentPosition: position.value || null,
      customPositions: customPositions.value
    })
  } catch {
    // 保存失败不阻断面试（岗位与勾选本轮会话内仍生效）
  }
}

// 恢复持久化岗位设置：需等分组列表加载完成后再勾选标签
async function loadPositionSetting() {
  try {
    const setting = await interviewApi.positionSetting()
    customPositions.value = setting?.customPositions || []
    if (setting?.currentPosition) {
      position.value = setting.currentPosition
      applyPositionTags(setting.currentPosition)
    }
  } catch {
    // 加载失败静默降级：不预设岗位
  }
}

function onDocumentClick(event) {
  if (positionPickerOpen.value && positionPickerRef.value && !positionPickerRef.value.contains(event.target)) {
    positionPickerOpen.value = false
  }
}

// 自定义岗位模态窗：补充岗位名 + 勾选绑定标签，保存后自动选中该岗位
const customModalOpen = ref(false)
const customModalError = ref('')
const customPositionName = ref('')
const customPositionTags = ref([])

function openCustomPositionModal() {
  positionPickerOpen.value = false
  customModalError.value = ''
  customPositionName.value = position.value && !PRESET_POSITIONS.some((p) => p.name === position.value) ? position.value : ''
  // 预填当前已勾选标签，便于在此基础上微调
  customPositionTags.value = [...selectedCategories.value]
  customModalOpen.value = true
}

function saveCustomPosition() {
  const name = customPositionName.value.trim()
  if (!name) {
    customModalError.value = '请输入岗位名称'
    return
  }
  if (PRESET_POSITIONS.some((p) => p.name === name)) {
    customModalError.value = '该名称与预设岗位重复，请换一个'
    return
  }
  const tags = [...customPositionTags.value]
  const existing = customPositions.value.findIndex((p) => p.name === name)
  if (existing >= 0) {
    customPositions.value[existing] = { name, tags }
  } else {
    customPositions.value.push({ name, tags })
  }
  customModalOpen.value = false
  selectPosition(name)
}

function deleteCustomPosition(name) {
  customPositions.value = customPositions.value.filter((p) => p.name !== name)
  if (position.value === name) {
    position.value = ''
  }
  savePositionSetting()
}

// 额度横幅三状态：有 Key 无限制 / 无 Key 剩余额度 / 额度用完引导配置（含充值引导）
const quotaInfo = ref(null)

// 计费模型选择（仅计费开关开启时呈现）：默认系统模型，付费模型需余额支撑；
// 余额耗尽横幅：计费场次回合预检中断后的充值引导（与开局 402 同一入口）
const modelOptions = ref([])
const selectedModel = ref('')
const insufficientBalanceHint = ref(false)

async function loadBillingModels() {
  if (!billingState.enabled) {
    return
  }
  try {
    modelOptions.value = (await billingApi.models()) || []
  } catch {
    // 模型价目加载失败不阻断开局（后端使用默认模型）
  }
}

const chatBox = ref(null)

const confirmFinish = ref(false)
const phaseBanner = ref('')
const statusSheetOpen = ref(false)
let phaseBannerTimer = null

// 顶部进度条阶段定义，与后端状态机环节对齐
const STAGES = ['OPENING', 'BASICS', 'PROJECT', 'DEEP', 'CLOSING']

const stageIndex = computed(() => {
  // 深度训练中用进入前的主面试阶段（returnState）定位，避免进度条跳到末尾
  const state = status.value?.state === 'DEEP_TRAINING'
    ? status.value?.returnState || 'BASICS'
    : status.value?.state
  const index = STAGES.indexOf(state)
  return index < 0 ? STAGES.length - 1 : index
})

const stagePercent = computed(() => ((stageIndex.value + 1) / STAGES.length) * 100)

// 仅出题环节可标记「已掌握/不知道」；开场（自我介绍）、收尾与深度训练中不可用
const canMark = computed(() => ['BASICS', 'PROJECT', 'DEEP'].includes(status.value?.state))

// 深度训练子流程进行中（徽章 + 退出按钮 + 禁用跳过）
const deepTrainingActive = computed(() => status.value?.deepTrainingActive === true)

const progressText = computed(() => {
  if (!status.value) {
    return ''
  }
  return `${status.value.askedCount} / ${status.value.plannedTotal}`
})

const isFinished = computed(() => status.value?.state === 'FINISHED')

// 短场免费：问答不足 5 题的场次不消耗免费额度且不记录历史，结束确认时提示用户（仅免费额度用户展示）
const finishFreeOfCharge = computed(() =>
  !quotaInfo.value?.hasOwnKey && (status.value?.askedCount ?? 0) < 5)

const modeLabel = computed(() => (mode.value === 'training' ? '训练模式' : '实战模式'))

const quotaBannerClass = computed(() => {
  if (!quotaInfo.value || quotaInfo.value.hasOwnKey) {
    return 'success'
  }
  if (!quotaInfo.value.enabled || quotaInfo.value.remaining > 0) {
    return ''
  }
  return 'danger'
})

const difficultyClass = computed(() => {
  const label = status.value?.difficultyLabel
  if (label === '困难') return 'danger'
  if (label === '简单') return 'success'
  return ''
})

function scoreClass(score) {
  if (score >= 7) return 'success'
  if (score >= 4) return 'warning'
  return 'danger'
}

// 阶段切换（如基础题 → 项目题）：淡入淡出横幅提示阶段名
watch(
  () => status.value?.phaseLabel,
  (label, oldLabel) => {
    if (label && oldLabel && label !== oldLabel) {
      phaseBanner.value = label
      clearTimeout(phaseBannerTimer)
      phaseBannerTimer = setTimeout(() => {
        phaseBanner.value = ''
      }, 1800)
    }
  }
)

function isStreamingItem(item) {
  return (
    sending.value &&
    item.role === 'assistant' &&
    item === messages.value[messages.value.length - 1] &&
    item.score == null
  )
}

onMounted(async () => {
  document.addEventListener('click', onDocumentClick)
  // 开始卡片展示简历选择：一份自动选中，多份下拉选择
  try {
    resumes.value = await resumeApi.list()
    if (resumes.value.length === 1) {
      selectedResumeId.value = resumes.value[0].id
    }
  } catch {
    // 简历列表加载失败不阻断面试开始（后端会降级为通用项目题）
  }
  await loadCategories()
  // 恢复持久化岗位设置（选择与自动勾选一直保留直到用户更改）
  await loadPositionSetting()
  if (!position.value) {
    // 未选岗位：按简历推荐默认勾选；已选岗位则以岗位绑定的标签为准
    await loadRecommendedCategories()
    for (const name of recommendedCategories.value) {
      if (!selectedCategories.value.includes(name)) {
        selectedCategories.value.push(name)
      }
    }
  } else {
    loadRecommendedCategories()
  }
  refreshQuota()
  // 计费开关与模型价目：导航入口由 App 顶栏同步拉取，此处确保直达页面也有最新状态
  refreshBillingState().then(loadBillingModels)
  // 刷新页面后恢复进行中的会话（状态栏与当前题；历史消息不回放）
  const saved = sessionStorage.getItem(SESSION_KEY)
  if (saved) {
    try {
      await restoreSession(saved)
    } catch {
      sessionStorage.removeItem(SESSION_KEY)
      sessionStorage.removeItem(MODE_KEY)
    }
    return
  }
  // 本地无会话记录：查询后端暂存的未完成面试（深入模块跳转专项训练后回来续考）
  try {
    resumableSession.value = await interviewApi.activeSession()
  } catch {
    // 查询失败静默降级：不展示续考入口
  }
})

onUnmounted(() => {
  document.removeEventListener('click', onDocumentClick)
})

// 恢复既有会话：刷新恢复与「继续未完成的面试」共用；
// 凭 status 完整重建对话（开场话术/历史回合/当前题），未完成回合轮询续接
async function restoreSession(saved) {
  status.value = await interviewApi.status(saved)
  sessionId.value = saved
  mode.value = status.value?.mode || sessionStorage.getItem(MODE_KEY) || 'practice'
  phase.value = 'active'
  if (status.value?.evaluating) {
    // 回合进行中刷新（评估/出题未完）：先展示已落库内容，轮询等回合完成后再补全
    messages.value = buildMessagesFromStatus(status.value)
    thinking.value = true
    thinkingText.value = '正在续接你上次提交的回答，评估中…'
    try {
      const latest = await waitForEvaluation(saved)
      if (latest) {
        status.value = latest
      }
    } finally {
      thinking.value = false
      thinkingText.value = ''
    }
  }
  messages.value = buildMessagesFromStatus(status.value)
  if (isFinished.value) {
    messages.value.push({ role: 'assistant', content: '本次面试已结束，可前往「历史报告」查看或生成报告。' })
  }
  scrollDown()
}

// 轮询等待未完成回合收尾（2.5s 间隔，最长 3 分钟）；会话过期等异常向上抛出由调用方清理本地会话
async function waitForEvaluation(id) {
  for (let i = 0; i < 72; i++) {
    await new Promise((resolve) => setTimeout(resolve, 2500))
    const latest = await interviewApi.status(id)
    status.value = latest
    if (!latest.evaluating) {
      return latest
    }
  }
  return null
}

// 凭 status 重建对话列表：开场话术 → 每个历史回合（题目→回答→导师点评+得分徽章）→ 当前待作答题
function buildMessagesFromStatus(st) {
  const list = []
  if (st?.openingMessage || st?.state === 'OPENING') {
    list.push({
      role: 'assistant',
      content: st.openingMessage || '你好！请先做一个简短的自我介绍（可包含项目经历与熟悉的技术栈）。'
    })
  }
  const training = st?.mode === 'training'
  for (const rec of st?.history || []) {
    const turn = []
    if (rec.question) {
      turn.push({ role: 'assistant', content: rec.question, followUp: !!rec.followUp })
    }
    if (rec.userAnswer) {
      turn.push({ role: 'user', content: rec.userAnswer })
    }
    if (training && rec.mentorComment) {
      turn.push({ role: 'assistant', content: rec.mentorComment })
    }
    // 得分徽章与「具体分析」数据挂在本回合最后一个气泡（对齐实时流的展示语义）
    if (training && turn.length) {
      const last = turn[turn.length - 1]
      last.score = rec.score
      last.evaluation = rec.evaluation || null
      last.category = rec.knowledgePoint || null
    }
    list.push(...turn)
  }
  if (st?.state !== 'FINISHED' && st?.currentQuestion) {
    list.push({ role: 'assistant', content: st.currentQuestion, followUp: !!st.currentQuestionFollowUp })
  }
  return list
}

// 继续未完成的面试：写回本地会话标记后按刷新恢复同逻辑还原
async function resumeSavedInterview() {
  const target = resumableSession.value
  if (!target || sending.value) {
    return
  }
  sending.value = true
  error.value = ''
  try {
    sessionStorage.setItem(SESSION_KEY, target.sessionId)
    sessionStorage.setItem(MODE_KEY, target.mode || 'practice')
    await restoreSession(target.sessionId)
    resumableSession.value = null
  } catch (e) {
    sessionStorage.removeItem(SESSION_KEY)
    sessionStorage.removeItem(MODE_KEY)
    resumableSession.value = null
    error.value = classifyError(e).message
  } finally {
    sending.value = false
  }
}

// 放弃暂存面试：正常结束并归档报告后清空入口
async function discardSavedInterview() {
  confirmDiscard.value = false
  const target = resumableSession.value
  if (!target) {
    return
  }
  try {
    const outcome = await interviewApi.finish(target.sessionId)
    if (outcome && outcome.archived === false) {
      toast.info('已放弃暂存的面试：本场问答不足 5 题，未消耗免费次数，也未记录到历史')
      refreshQuota()
    } else {
      toast.info('已放弃暂存的面试，报告已归档至历史记录')
    }
  } catch (e) {
    toast.error(classifyError(e).message)
  }
  resumableSession.value = null
}

async function refreshQuota() {
  try {
    quotaInfo.value = await quotaApi.get()
  } catch {
    // 额度信息加载失败不阻断面试开始（后端仍会校验）
  }
}

function proceedToModeSelect() {
  error.value = ''
  phase.value = 'mode-select'
}

async function startInterview(selectedMode) {
  sending.value = true
  error.value = ''
  try {
    const data = await interviewApi.start(position.value.trim(), selectedResumeId.value || null, selectedMode,
      selectedCategories.value.length ? selectedCategories.value : null, includeAlgorithm.value || null,
      selectedModel.value || null)
    mode.value = selectedMode
    sessionStorage.setItem(MODE_KEY, selectedMode)
    sessionId.value = data.sessionId
    status.value = data.status
    sessionStorage.setItem(SESSION_KEY, data.sessionId)
    messages.value = [{ role: 'assistant', content: data.openingMessage }]
    phase.value = 'active'
    // 开始面试会扣减一次额度，刷新横幅显示
    refreshQuota()
  } catch (e) {
    if (e.code === 'QUOTA_EXCEEDED') {
      error.value = billingState.enabled
        ? '今日免费额度已用完，可充值余额按计费模式继续，或前往「设置」配置自己的 API Key'
        : '今日免费额度已用完，可前往「设置」配置自己的 API Key 继续使用'
      toast.error(e.message || '今日免费额度已用完')
      refreshQuota()
      refreshBillingState()
    } else if (e.code === 'INSUFFICIENT_BALANCE') {
      error.value = e.message || '余额不足，请充值后继续'
      toast.error(e.message || '余额不足，请充值后继续')
      refreshBillingState()
    } else {
      error.value = classifyError(e).message
    }
  } finally {
    sending.value = false
  }
}

function onEnterSend(event) {
  // Enter 发送，Shift+Enter 换行
  if (event.shiftKey) {
    return
  }
  event.preventDefault()
  send()
}

function send() {
  const text = answer.value.trim()
  if (!text || sending.value || isFinished.value) {
    return
  }
  messages.value.push({ role: 'user', content: text })
  answer.value = ''
  runStreamingTurn((callbacks) => askStream(sessionId.value, text, callbacks), () => retrySend(text))
}

function retrySend(text) {
  // 重试时用户消息已在列表中，只重新发起请求
  runStreamingTurn((callbacks) => askStream(sessionId.value, text, callbacks), () => retrySend(text))
}

// 「已掌握」：绿勾标记后本题直接 pass（不计分不入历史），SSE 返回下一题
function markMastered() {
  if (!canMark.value || sending.value || isFinished.value) {
    return
  }
  runStreamingTurn((callbacks) => masteredStream(sessionId.value, callbacks), markMastered)
}

// 「不知道」：等价作答「不知道」走完整评估反馈（强制 0 分）+ 红叉
function markDontKnow() {
  if (!canMark.value || sending.value || isFinished.value) {
    return
  }
  runStreamingTurn((callbacks) => dontknowStream(sessionId.value, callbacks), markDontKnow)
}

// 主动退出深度训练：恢复主面试并出下一题（仅兼容存量会话，新入口已下线）
function exitDeepTraining() {
  if (sending.value || isFinished.value || !deepTrainingActive.value) {
    return
  }
  runStreamingTurn((callbacks) => deepTrainingExitStream(sessionId.value, callbacks), exitDeepTraining)
}

// ---------- SSE 流式渲染：chunk 拆单字符入队 + 5~15ms 节流，营造逐字输出感 ----------
// 后端按 delta 即时推送，但单个 chunk 可能包含多字；拆成单字符后无论上游 chunk 多大都呈逐字效果
const chunkQueue = []
let queueTimer = null
let activeStreamMessage = null
let pendingDone = null
// segment 分段帧：导师反馈与下一题分属两个气泡，需等渲染队列排空后才新建气泡
let pendingSegments = 0

async function runStreamingTurn(request, retry) {
  sending.value = true
  thinking.value = true
  // 新回合发起即清除余额不足横幅（充值后回来继续作答的场景）
  insufficientBalanceHint.value = false
  // 必须持有 push 后返回的响应式代理：直接改原始对象不会触发重渲染，
  // 打字机过程将全程不可见、回合结束才一次性涌出（「一口气输出」）
  messages.value.push({ role: 'assistant', content: '' })
  const assistantMessage = messages.value[messages.value.length - 1]
  activeStreamMessage = assistantMessage
  scrollDown()
  try {
    await request({
      onMessage: enqueueChunk,
      onSegment: queueSegment,
      onProgress: (text) => {
        thinkingText.value = text
      },
      onDone: (result) => queueDone(assistantMessage, result),
      onError: (e) => failStream(assistantMessage, e, retry)
    })
  } catch (e) {
    failStream(assistantMessage, e, retry)
  } finally {
    // 兜底：连接结束但无 done 事件且队列已排空时释放输入状态；
    // 若已流出部分内容则连接异常中断，提示刷新恢复（后端状态机已推进，status 接口能还原当前题）
    if (activeStreamMessage === assistantMessage && chunkQueue.length === 0 && !queueTimer && !pendingDone && pendingSegments === 0) {
      if (assistantMessage.content) {
        assistantMessage.comment = '连接已中断，请刷新页面查看最新进度'
      }
      endStream()
    }
    scrollDown()
  }
}

function enqueueChunk(chunk) {
  // 拆成单字符入队：队列天然按字符节流，渲染节奏与上游 chunk 大小解耦
  for (const char of chunk) {
    chunkQueue.push(char)
  }
  scheduleDrain()
}

function scheduleDrain() {
  if (queueTimer) {
    return
  }
  queueTimer = setTimeout(drainNext, 5 + Math.random() * 10)
}

function drainNext() {
  queueTimer = null
  const chunk = chunkQueue.shift()
  if (activeStreamMessage) {
    if (!activeStreamMessage.content) {
      // 第一个字符渲染出来才收起「正在思考…」动画
      thinking.value = false
    }
    activeStreamMessage.content += chunk
    scrollDown()
  }
  if (chunkQueue.length > 0) {
    scheduleDrain()
    return
  }
  flushSegments()
  if (pendingDone) {
    const done = pendingDone
    pendingDone = null
    endStream()
    handleAskDone(done.assistantMessage, done.result)
  }
}

// 消费待处理的分段帧：新建空气泡承接后续内容（如下一题），并恢复思考动画
function flushSegments() {
  while (pendingSegments > 0) {
    pendingSegments--
    startNewBubble()
  }
}

function queueSegment() {
  if (chunkQueue.length === 0 && !queueTimer && !pendingDone) {
    startNewBubble()
  } else {
    pendingSegments++
  }
}

function startNewBubble() {
  // 当前气泡还是空的（如分段帧早于首字到达）：复用它，不重复创建
  if (!activeStreamMessage || !activeStreamMessage.content) {
    return
  }
  messages.value.push({ role: 'assistant', content: '' })
  // 同 runStreamingTurn：持有响应式代理，逐字追加才能实时渲染
  activeStreamMessage = messages.value[messages.value.length - 1]
  thinking.value = true
  scrollDown()
}

function queueDone(assistantMessage, result) {
  if (chunkQueue.length === 0 && !queueTimer) {
    flushSegments()
    endStream()
    handleAskDone(assistantMessage, result)
  } else {
    // 渲染队列未排空：先逐字放完再结算评分/下一题
    pendingDone = { assistantMessage, result }
  }
}

function failStream(assistantMessage, e, retry) {
  chunkQueue.length = 0
  pendingDone = null
  pendingSegments = 0
  if (queueTimer) {
    clearTimeout(queueTimer)
    queueTimer = null
  }
  if (!assistantMessage.content) {
    messages.value.pop()
  }
  endStream()
  if (e?.code === 'INSUFFICIENT_BALANCE') {
    // 计费场次余额耗尽：展示充值引导横幅（非连接异常，不提供重试）
    insufficientBalanceHint.value = true
    refreshBillingState()
    toast.error(e.message || '余额不足，请充值后继续')
    return
  }
  notifyError(e, retry)
}

function endStream() {
  sending.value = false
  thinking.value = false
  thinkingText.value = ''
  activeStreamMessage = null
  scrollDown()
}

async function handleAskDone(assistantMessage, result) {
  assistantMessage.score = result.score
  // 训练模式详细反馈（亮点/不足/改进回答）进「具体分析」小窗，不再内联渲染；点评文字不再展示
  assistantMessage.evaluation = result.evaluation
  // 任务 4：记录本题所属分组，供反馈气泡旁「深入该模块」按钮跳转专项训练
  assistantMessage.category = result.status?.lastAnswerCategory || null
  // 刚发出的题目若为追问/深度训练题，气泡打上标识
  assistantMessage.followUp = !!result.status?.currentQuestionFollowUp
  status.value = result.status
  if (result.status?.mode) {
    mode.value = result.status.mode
  }
  if (result.action === 'FINISH' || result.status?.state === 'FINISHED') {
    await finishAndShowReport()
  }
}

function requestFinish() {
  confirmFinish.value = true
}

function cancelFinish() {
  confirmFinish.value = false
}

async function finishInterview() {
  confirmFinish.value = false
  await finishAndShowReport()
}

async function finishAndShowReport() {
  if (phase.value === 'finishing') {
    return
  }
  phase.value = 'finishing'
  try {
    // finish 触发报告生成与归档；短场（问答不足门槛）不归档：提示后回开始页，不跳报告页
    const outcome = await interviewApi.finish(sessionId.value)
    sessionStorage.removeItem(SESSION_KEY)
    sessionStorage.removeItem(MODE_KEY)
    if (outcome && outcome.archived === false) {
      toast.info('本场问答不足 5 题，未消耗免费次数，也未记录到历史')
      refreshQuota()
      sessionId.value = ''
      status.value = null
      messages.value = []
      error.value = ''
      phase.value = 'idle'
      return
    }
    router.push(`/report/${sessionId.value}`)
  } catch (e) {
    notifyError(e, finishAndShowReport)
    phase.value = 'active'
  }
}

function scrollDown() {
  nextTick(() => {
    if (chatBox.value) {
      chatBox.value.scrollTop = chatBox.value.scrollHeight
    }
  })
}
</script>

<template>
  <div class="page">
    <h1 class="page-title">模拟面试</h1>

    <!-- 开始卡片 -->
    <div v-if="phase === 'idle'" class="card start-card">
      <div class="start-head">
        <h2>开始一场模拟面试</h2>
        <!-- 岗位选择器：预设/自定义岗位下拉，选择持久保留直到用户更改 -->
        <div ref="positionPickerRef" class="position-picker">
          <button type="button" class="position-picker-btn" :class="{ chosen: !!position }" :disabled="sending" @click="positionPickerOpen = !positionPickerOpen">
            <span class="position-picker-label">{{ position || '选择面试岗位' }}</span>
            <span class="picker-caret">▾</span>
          </button>
          <div v-if="positionPickerOpen" class="position-dropdown">
            <button v-if="position" type="button" class="position-option clear-option" @click="clearPosition">✕ 不按岗位限定出题</button>
            <div class="position-group-label">预设岗位</div>
            <button
              v-for="opt in PRESET_POSITIONS"
              :key="opt.name"
              type="button"
              class="position-option"
              :class="{ active: position === opt.name }"
              @click="selectPosition(opt.name)"
            >
              <span class="position-name">{{ opt.name }}</span>
              <span class="muted position-tag-count">{{ opt.tags.length }} 项技术栈</span>
              <span v-if="position === opt.name" class="position-check">✓</span>
            </button>
            <template v-if="customPositions.length">
              <div class="position-group-label">自定义岗位</div>
              <div v-for="opt in customPositions" :key="opt.name" class="position-option-row">
                <button
                  type="button"
                  class="position-option"
                  :class="{ active: position === opt.name }"
                  @click="selectPosition(opt.name)"
                >
                  <span class="position-name">{{ opt.name }}</span>
                  <span class="muted position-tag-count">{{ opt.tags.length }} 项技术栈</span>
                  <span v-if="position === opt.name" class="position-check">✓</span>
                </button>
                <button type="button" class="position-delete" title="删除该岗位" @click="deleteCustomPosition(opt.name)">×</button>
              </div>
            </template>
            <div class="position-dropdown-divider"></div>
            <button type="button" class="position-option add-option" @click="openCustomPositionModal">＋ 自定义岗位…</button>
          </div>
        </div>
      </div>
      <p v-if="position && currentPositionTags.length" class="position-applied muted">
        已按岗位「{{ position }}」自动勾选 {{ currentPositionTags.length }} 项技术栈，可在下方出题范围中微调
      </p>
      <!-- 任务 4：暂存续考入口（深入模块跳专项训练后回来接着考） -->
      <div v-if="resumableSession" class="resume-banner">
        <span>
          ⏸️ 你有一场未完成的面试（{{ resumableSession.phaseLabel || '进行中' }} · 已作答 {{ resumableSession.askedCount ?? 0 }} 题），进度已暂存。
        </span>
        <span class="resume-actions">
          <button type="button" :disabled="sending" @click="resumeSavedInterview">继续未完成的面试</button>
          <button type="button" class="ghost" :disabled="sending" @click="confirmDiscard = true">放弃</button>
        </span>
      </div>
      <p class="muted">
        面试分为基础考察、项目经历、深度追问三个环节，AI 面试官会根据你的回答动态追问与调整难度，结束后生成详细反馈报告。
      </p>
      <!-- 额度横幅：有 Key 无限制 / 剩余额度 / 额度用完引导配置 -->
      <div v-if="quotaInfo" class="quota-banner" :class="quotaBannerClass">
        <template v-if="quotaInfo.hasOwnKey">
          <span>🔑 使用自己的 API Key（无限制）</span>
        </template>
        <template v-else-if="!quotaInfo.enabled || quotaInfo.remaining > 0">
          <span>🎁 今日剩余免费次数：{{ quotaInfo.remaining }}/{{ quotaInfo.dailyLimit }}</span>
        </template>
        <template v-else>
          <span>⚠️ 今日免费额度已用完</span>
          <span v-if="billingState.enabled && billingState.balanceCents > 0" class="quota-billing-note">
            可用余额 ¥{{ (billingState.balanceCents / 100).toFixed(2) }}，开始后自动按 token 用量计费
          </span>
          <button v-if="billingState.enabled" type="button" class="ghost quota-config-btn" @click="router.push('/billing')">
            去充值
          </button>
          <button type="button" class="ghost quota-config-btn" @click="router.push('/settings')">
            配置 API Key
          </button>
        </template>
      </div>
      <!-- 计费模型选择（仅计费开关开启）：默认系统模型，付费模型无余额时锁定 -->
      <div v-if="billingState.enabled && modelOptions.length" class="model-row">
        <label class="muted" for="model-select">模型选择：</label>
        <select id="model-select" v-model="selectedModel" :disabled="sending">
          <option value="">系统默认模型</option>
          <option
            v-for="item in modelOptions"
            :key="item.id"
            :value="item.id"
            :disabled="item.paidOnly && billingState.balanceCents <= 0"
          >
            {{ item.name }}{{ item.paidOnly ? (billingState.balanceCents > 0 ? '（付费）' : ' 🔒 付费·余额不足') : '（免费）' }}
          </option>
        </select>
      </div>
      <form class="start-row" @submit.prevent="proceedToModeSelect">
        <button type="submit" :disabled="sending">开始面试</button>
      </form>
      <div class="resume-row">
        <template v-if="resumes.length > 1">
          <label class="muted" for="resume-select">选择本次面试使用的简历：</label>
          <select id="resume-select" v-model="selectedResumeId" :disabled="sending">
            <option :value="null">不使用简历（通用项目题）</option>
            <option v-for="item in resumes" :key="item.id" :value="item.id">
              {{ item.name || '未命名候选人' }}
            </option>
          </select>
        </template>
        <template v-else-if="resumes.length === 1">
          <span class="muted">已自动关联简历：</span>
          <span class="badge">{{ resumes[0].name || '未命名候选人' }}</span>
        </template>
        <template v-else>
          <span class="muted">暂无简历，面试将使用通用项目题；可先去 <RouterLink to="/resume">简历管理</RouterLink> 创建</span>
        </template>
      </div>
      <!-- 出题范围：勾选资料分组，不勾选按默认官方题库；岗位/标签同时作为面试官设定注入 -->
      <div class="category-row">
        <div class="category-head">
          <span class="muted">出题范围（可选，不勾选使用默认题库；所选标签会一并提供给面试官作为设定）：</span>
          <RouterLink class="category-link" to="/library">管理资源库 →</RouterLink>
        </div>
        <template v-if="officialOptions.length">
          <div class="chip-group-title muted">官方题库标签</div>
          <div class="category-chips">
            <label v-for="opt in officialOptions" :key="opt.name" class="chip-check">
              <input v-model="selectedCategories" type="checkbox" :value="opt.name" :disabled="sending" />
              <span>
                {{ opt.name }}
                <span v-if="recommendedCategories.includes(opt.name)" class="recommend-star" title="根据简历推荐">⭐</span>
              </span>
            </label>
          </div>
        </template>
        <template v-if="customOptions.length">
          <div class="chip-group-title muted">我的标签（自行添加）</div>
          <div class="category-chips">
            <label v-for="opt in customOptions" :key="opt.name" class="chip-check">
              <input v-model="selectedCategories" type="checkbox" :value="opt.name" :disabled="sending" />
              <span>{{ opt.name }}</span>
            </label>
          </div>
        </template>
        <!-- 算法开关（任务 12）：开启后 DEEP 阶段掺入算法分组手写编程题 -->
        <label class="algorithm-toggle">
          <input v-model="includeAlgorithm" type="checkbox" :disabled="sending" />
          <span>包含算法手写编程题<span class="muted">（开启后深度环节按难度掺入高频算法题）</span></span>
        </label>
      </div>
      <p v-if="error" class="error-text">{{ error }}</p>
    </div>

    <!-- 模式选择卡片：开始面试前的前置步骤 -->
    <div v-else-if="phase === 'mode-select'" class="card start-card">
      <h2>选择面试模式</h2>
      <p class="muted">模式决定追问节奏与反馈方式，开始后本场不可切换。</p>
      <div class="mode-options">
        <button type="button" class="mode-card" :disabled="sending" @click="startInterview('training')">
          <span class="mode-name">🎓 训练模式</span>
          <span class="muted mode-desc">
            每题先给导师式人性化点评，再出下一题；得分旁可点「具体分析」查看亮点/不足/改进后的回答，适合针对性复习。
          </span>
        </button>
        <button type="button" class="mode-card" :disabled="sending" @click="startInterview('practice')">
          <span class="mode-name">⚔️ 实战模式</span>
          <span class="muted mode-desc">
            模拟真实面试节奏，过程中不作评价与评分，按表现自动追问；完整面试结束后给出详尽报告。
          </span>
        </button>
      </div>
      <button type="button" class="ghost back-btn" :disabled="sending" @click="phase = 'idle'">← 返回</button>
      <p v-if="error" class="error-text">{{ error }}</p>
    </div>

    <!-- 进行中 -->
    <template v-else>
      <!-- 计费场次余额耗尽：回合预检中断后的充值引导横幅 -->
      <div v-if="insufficientBalanceHint" class="billing-hint-banner">
        <span>💰 余额不足，本场已暂停，充值后可继续作答。</span>
        <button type="button" @click="router.push('/billing')">去充值</button>
      </div>
      <!-- 顶部进度条：当前阶段 / 总阶段 -->
      <div class="stage-progress-wrap">
        <div class="stage-progress-track">
          <div class="stage-progress-fill" :style="{ width: stagePercent + '%' }"></div>
        </div>
        <span class="muted stage-progress-text">
          阶段 {{ stageIndex + 1 }} / {{ STAGES.length }} · {{ status?.phaseLabel || '—' }}
        </span>
      </div>

      <div class="interview-layout">
        <div class="interview-main">
          <!-- 平板档：状态折叠在顶部；桌面档隐藏（信息移到右侧边栏），手机档隐藏（收入底部抽屉） -->
          <div class="card status-bar">
            <span class="badge mode-badge">{{ modeLabel }}</span>
            <span class="badge">{{ status?.phaseLabel || '—' }}</span>
            <span :class="['badge', difficultyClass]">难度：{{ status?.difficultyLabel || '—' }}</span>
            <span
              v-if="status?.currentQuestionFollowUp && !deepTrainingActive"
              class="badge warning"
            >
              🔄 追问 {{ status?.followUpsUsed }}/{{ status?.followUpLimit }}
            </span>
            <span v-if="deepTrainingActive" class="badge deep-badge">
              🎯 深度训练 · 达标 {{ status?.deepTrainingPassStreak ?? 0 }}/2 · 第 {{ status?.deepTrainingAsked ?? 0 }}/5 题
            </span>
            <button
              v-if="deepTrainingActive"
              type="button"
              class="ghost deep-exit-btn"
              :disabled="sending"
              @click="exitDeepTraining"
            >
              退出深度训练
            </button>
            <span class="muted progress">进度 {{ progressText }} 题</span>
            <span v-if="mode === 'training' && status?.averageScore != null" class="muted">
              平均 {{ status.averageScore.toFixed(1) }} 分
            </span>
            <button v-if="!isFinished" class="ghost finish-btn" :disabled="phase === 'finishing'" @click="requestFinish">
              结束面试
            </button>
          </div>

      <div ref="chatBox" class="card chat-box">
        <div v-for="(item, index) in messages" :key="index" :class="['bubble-row', item.role, { followup: item.followUp }]">
          <div class="bubble">
            <div v-if="item.followUp" class="followup-tag"><span class="badge warning">🔄 追问</span></div>
            <div class="bubble-content">
              <div v-if="item.role === 'assistant'" class="md" v-html="renderMarkdown(item.content)"></div>
              <template v-else>{{ item.content }}</template>
              <span v-if="item.comment" class="muted comment">{{ item.comment }}</span>
              <span v-if="isStreamingItem(item)" class="stream-cursor"></span>
            </div>
            <div v-if="item.score != null" class="score-line">
              <span :class="['badge', scoreClass(item.score)]">得分 {{ item.score }}</span>
              <!-- 训练模式：得分不再附点评，亮点/不足/改进回答收入「具体分析」小窗 -->
              <div v-if="mode === 'training' && item.evaluation" class="analysis-anchor">
                <button type="button" class="ghost small analysis-btn" @click="toggleAnalysis(index, $event)">
                  {{ analysisOpenIndex === index ? '收起分析' : '🔍 具体分析' }}
                </button>
                <div
                  v-if="analysisOpenIndex === index"
                  class="analysis-popover card"
                  :class="{ below: analysisPlacement.below }"
                  :style="{ maxHeight: analysisPlacement.maxHeight }"
                >
                  <div class="analysis-head">
                    <strong>本题分析</strong>
                    <button type="button" class="analysis-close" @click="analysisOpenIndex = null">✕ 关闭</button>
                  </div>
                  <div class="analysis-body">
                    <div v-if="item.evaluation.goodPoints?.length" class="eval-section good">
                      <div class="eval-title">✅ 亮点</div>
                      <ul>
                        <li v-for="(point, idx) in item.evaluation.goodPoints" :key="'g' + idx">{{ point }}</li>
                      </ul>
                    </div>
                    <div v-if="item.evaluation.badPoints?.length" class="eval-section bad">
                      <div class="eval-title">❌ 不足</div>
                      <ul>
                        <li v-for="(point, idx) in item.evaluation.badPoints" :key="'b' + idx">{{ point }}</li>
                      </ul>
                    </div>
                    <div v-if="item.evaluation.improvedAnswer" class="eval-section improved">
                      <div class="eval-title">💡 改进后的回答</div>
                      <blockquote class="md" v-html="renderMarkdown(item.evaluation.improvedAnswer)"></blockquote>
                    </div>
                    <p v-if="!item.evaluation.goodPoints?.length && !item.evaluation.badPoints?.length && !item.evaluation.improvedAnswer" class="muted">
                      本题暂无详细分析内容
                    </p>
                  </div>
                </div>
              </div>
              <!-- 任务 4：每题反馈后常显「深入该模块」，跳转该题所属分组的专项训练（面试暂存续考） -->
              <button
                v-if="mode === 'training' && item.category && isCategoryAvailable(item.category)"
                type="button"
                class="ghost small deep-dive-btn"
                :disabled="sending"
                @click="requestDeepDive(item.category)"
              >
                🎯 深入该模块
              </button>
            </div>
          </div>
        </div>
        <div v-if="thinking" class="bubble-row assistant">
          <div class="bubble typing-bubble" aria-label="面试官思考中">
            <span class="typing-dot"></span><span class="typing-dot"></span><span class="typing-dot"></span>
            <span v-if="thinkingText" class="thinking-text">{{ thinkingText }}</span>
          </div>
        </div>
      </div>

      <form v-if="!isFinished" class="card answer-row" @submit.prevent="send()">
        <textarea
          v-model="answer"
          rows="2"
          :placeholder="answerPlaceholder"
          :disabled="sending"
          @keydown.enter="onEnterSend"
        ></textarea>
        <div class="answer-actions">
          <button
            v-if="mode === 'training'"
            type="button"
            class="ghost mastered-btn"
            title="已掌握：本题直接 pass，不计分不入历史，后续出现概率降低"
            :disabled="sending || !canMark || deepTrainingActive"
            @click="markMastered"
          >
            ✓ 已掌握
          </button>
          <button
            v-if="mode === 'training'"
            type="button"
            class="ghost dontknow-btn"
            title="不知道：本题记 0 分并给出点评，后续出现频率增高"
            :disabled="sending || !canMark || deepTrainingActive"
            @click="markDontKnow"
          >
            ✗ 不知道
          </button>
          <button type="submit" :disabled="sending || !answer.trim()">发送</button>
        </div>
      </form>
        </div>

        <!-- 桌面档：右侧边栏展示面试状态 -->
        <aside class="card side-panel">
          <h3>面试状态</h3>
          <div class="side-row">
            <span class="muted">模式</span>
            <span class="badge">{{ modeLabel }}</span>
          </div>
          <div class="side-row">
            <span class="muted">当前环节</span>
            <span class="badge">{{ status?.phaseLabel || '—' }}</span>
          </div>
          <div class="side-row">
            <span class="muted">难度</span>
            <span :class="['badge', difficultyClass]">{{ status?.difficultyLabel || '—' }}</span>
          </div>
          <div class="side-row">
            <span class="muted">追问进度</span>
            <span class="badge warning">{{ status?.followUpsUsed ?? 0 }}/{{ status?.followUpLimit ?? 0 }}</span>
          </div>
          <div class="side-row">
            <span class="muted">题目进度</span>
            <span>{{ progressText || '—' }} 题</span>
          </div>
          <div v-if="mode === 'training'" class="side-row">
            <span class="muted">平均分</span>
            <span>{{ status?.averageScore != null ? status.averageScore.toFixed(1) : '—' }}</span>
          </div>
          <div v-if="deepTrainingActive" class="side-row">
            <span class="muted">深度训练</span>
            <span class="badge deep-badge">达标 {{ status?.deepTrainingPassStreak ?? 0 }}/2 · {{ status?.deepTrainingAsked ?? 0 }}/5 题</span>
          </div>
          <button
            v-if="!isFinished"
            class="secondary finish-btn-side"
            :disabled="phase === 'finishing'"
            @click="requestFinish"
          >
            结束面试
          </button>
        </aside>
      </div>

      <!-- 手机档：状态抽屉入口 -->
      <button class="status-fab" @click="statusSheetOpen = true">📊 状态</button>

      <!-- 手机档：底部抽屉（Bottom Sheet） -->
      <Transition name="sheet">
        <div v-if="statusSheetOpen" class="sheet-mask" @click="statusSheetOpen = false">
          <div class="bottom-sheet card" @click.stop>
            <div class="sheet-handle"></div>
            <h3>面试状态</h3>
            <div class="side-row">
              <span class="muted">模式</span>
              <span class="badge">{{ modeLabel }}</span>
            </div>
            <div class="side-row">
              <span class="muted">当前环节</span>
              <span class="badge">{{ status?.phaseLabel || '—' }}</span>
            </div>
            <div class="side-row">
              <span class="muted">难度</span>
              <span :class="['badge', difficultyClass]">{{ status?.difficultyLabel || '—' }}</span>
            </div>
            <div class="side-row">
              <span class="muted">追问进度</span>
              <span class="badge warning">{{ status?.followUpsUsed ?? 0 }}/{{ status?.followUpLimit ?? 0 }}</span>
            </div>
            <div class="side-row">
              <span class="muted">题目进度</span>
              <span>{{ progressText || '—' }} 题</span>
            </div>
            <div v-if="mode === 'training'" class="side-row">
              <span class="muted">平均分</span>
              <span>{{ status?.averageScore != null ? status.averageScore.toFixed(1) : '—' }}</span>
            </div>
            <div v-if="deepTrainingActive" class="side-row">
              <span class="muted">深度训练</span>
              <span class="badge deep-badge">达标 {{ status?.deepTrainingPassStreak ?? 0 }}/2 · {{ status?.deepTrainingAsked ?? 0 }}/5 题</span>
            </div>
            <button
              v-if="!isFinished"
              class="finish-btn-side"
              :disabled="phase === 'finishing'"
              @click="statusSheetOpen = false; requestFinish()"
            >
              结束面试
            </button>
          </div>
        </div>
      </Transition>

      <!-- 结束面试：报告生成进度遮罩 -->
      <div v-if="phase === 'finishing'" class="loading-overlay">
        <div class="card loading-progress">
          <div class="spinner"></div>
          <p>正在生成反馈报告...</p>
          <p class="muted">AI 正在汇总各题评分与能力维度，请稍候</p>
        </div>
      </div>

      <!-- 结束面试二次确认 -->
      <div v-if="confirmFinish" class="loading-overlay">
        <div class="card confirm-dialog">
          <h3>确定提前结束面试？</h3>
          <p class="muted">未作答的剩余题目将被跳过，报告将基于已作答题目生成（跳过的题计 0 分）。</p>
          <p v-if="finishFreeOfCharge" class="muted finish-free-note">
            💡 本次面试问答不足 5 题，结束面试不会扣除免费次数，也不会记录到历史。
          </p>
          <div class="confirm-actions">
            <button class="secondary" @click="cancelFinish">继续面试</button>
            <button @click="finishInterview">确认结束</button>
          </div>
        </div>
      </div>

      <!-- 任务 4：深入该模块二次确认（将暂存当前面试进度） -->
      <div v-if="confirmDeepDive" class="loading-overlay">
        <div class="card confirm-dialog">
          <h3>深入「{{ confirmDeepDive }}」模块？</h3>
          <p class="muted">将暂存当前面试进度，跳转该分组的专项训练；训练结束后回到本页可继续未完成的面试。</p>
          <div class="confirm-actions">
            <button class="secondary" @click="cancelDeepDive">继续面试</button>
            <button @click="goDeepDive">前往专项训练</button>
          </div>
        </div>
      </div>

      <!-- 任务 4：放弃暂存面试二次确认 -->
      <div v-if="confirmDiscard" class="loading-overlay">
        <div class="card confirm-dialog">
          <h3>放弃暂存的面试？</h3>
          <p class="muted">该场面试将按已作答题目正常结束并归档报告，之后无法继续。</p>
          <div class="confirm-actions">
            <button class="secondary" @click="confirmDiscard = false">再想想</button>
            <button @click="discardSavedInterview">确认放弃</button>
          </div>
        </div>
      </div>

      <!-- 阶段过渡横幅：淡入淡出 -->
      <Transition name="phase-fade">
        <div v-if="phaseBanner" class="phase-banner">进入：{{ phaseBanner }}</div>
      </Transition>
    </template>

    <!-- 自定义岗位模态窗：补充岗位名与对应技术栈标签，保存后持久生效 -->
    <div v-if="customModalOpen" class="loading-overlay" @click.self="customModalOpen = false">
      <div class="card confirm-dialog custom-position-dialog">
        <h3>自定义岗位</h3>
        <p class="muted">保存后可在岗位下拉中随时选用，岗位选择会一直保留直到你更改。</p>
        <input
          v-model="customPositionName"
          class="custom-position-input"
          placeholder="岗位名称，如：大数据开发工程师"
          maxlength="64"
          :disabled="sending"
        />
        <p class="muted custom-position-tip">选择该岗位对应的技术栈（官方题库与你的标签）：</p>
        <div class="custom-tag-grid">
          <label v-for="opt in categoryOptions" :key="'cp' + opt.name" class="chip-check">
            <input v-model="customPositionTags" type="checkbox" :value="opt.name" :disabled="sending" />
            <span>{{ opt.name }}<span v-if="opt.official" class="muted"> ·官方</span></span>
          </label>
        </div>
        <p v-if="customModalError" class="error-text">{{ customModalError }}</p>
        <div class="confirm-actions">
          <button class="secondary" :disabled="sending" @click="customModalOpen = false">取消</button>
          <button :disabled="sending" @click="saveCustomPosition">保存并使用</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.start-card h2 {
  margin-bottom: 8px;
}

/* ---------- 任务 4：续考横幅与深入该模块按钮 ---------- */
.resume-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin: 10px 0 4px;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 13px;
  background: #fff7e6;
  border: 1px solid #ffe3ad;
}

.resume-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

.deep-dive-btn {
  white-space: nowrap;
}

.start-row {
  display: flex;
  gap: 10px;
  margin-top: 16px;
}

.resume-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
}

.category-row {
  margin-top: 14px;
}

.algorithm-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  font-size: 14px;
  cursor: pointer;
}

.algorithm-toggle input {
  accent-color: var(--accent, #4f6ef7);
  width: 16px;
  height: 16px;
}

.category-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}

.category-link {
  font-size: 13px;
  white-space: nowrap;
}

.category-chips {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 8px;
}

.chip-check {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: 4px;
  font-size: 13px;
  cursor: pointer;
  user-select: none;
  background: #fff;
}

.chip-check span {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chip-check:has(input:checked) {
  border-color: var(--primary);
  color: var(--primary);
  background: rgba(64, 128, 255, 0.06);
}

.recommend-star {
  font-size: 12px;
}

/* 岗位选择器：标题行右侧下拉，预设/自定义岗位持久保存 */
.start-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.start-head h2 {
  margin-bottom: 0;
}

.position-picker {
  position: relative;
  flex-shrink: 0;
}

.position-picker-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 180px;
  max-width: 280px;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fff;
  /* 全局 button 默认白字，白底按钮必须显式指定深色文字，否则白底白字不可见 */
  color: var(--text);
  font-size: 14px;
  cursor: pointer;
  justify-content: space-between;
}

.position-picker-btn:hover {
  background: #fff;
  border-color: var(--primary);
}

.position-picker-btn:disabled {
  background: #fff;
  color: var(--text-light);
}

.position-picker-btn.chosen {
  border-color: var(--primary);
  color: var(--primary);
}

.position-picker-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.picker-caret {
  font-size: 12px;
  opacity: 0.7;
}

.position-dropdown {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  z-index: 30;
  min-width: 280px;
  max-width: 340px;
  max-height: 380px;
  overflow-y: auto;
  padding: 6px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--card, #fff);
  box-shadow: 0 8px 24px rgba(20, 30, 60, 0.12);
  display: flex;
  flex-direction: column;
}

.position-group-label {
  padding: 6px 10px 2px;
  font-size: 12px;
  color: var(--muted, #8a8f99);
}

.position-option {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: 6px;
  background: transparent;
  /* 全局 button 默认白字，白底下拉选项必须显式指定深色文字 */
  color: var(--text);
  font-size: 14px;
  text-align: left;
  cursor: pointer;
}

.position-option:hover {
  background: #f2f5fb;
}

.position-option.active {
  color: var(--primary);
  background: rgba(64, 128, 255, 0.06);
}

.position-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.position-tag-count {
  font-size: 12px;
  white-space: nowrap;
}

.position-check {
  color: var(--primary);
}

.position-option-row {
  display: flex;
  align-items: center;
  gap: 2px;
}

.position-option-row .position-option {
  flex: 1;
  min-width: 0;
}

.position-delete {
  flex-shrink: 0;
  width: 26px;
  height: 26px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--muted, #8a8f99);
  font-size: 16px;
  cursor: pointer;
}

.position-delete:hover {
  background: #fff1f0;
  color: #e5484d;
}

.clear-option {
  color: var(--muted, #8a8f99);
  font-size: 13px;
}

.add-option {
  color: var(--primary);
}

.position-dropdown-divider {
  height: 1px;
  margin: 4px 6px;
  background: var(--border);
}

.position-applied {
  margin: 0 0 4px;
  font-size: 12px;
}

/* 自定义岗位模态窗 */
.custom-position-dialog {
  width: min(560px, 92vw);
}

.custom-position-input {
  width: 100%;
  margin: 10px 0 4px;
}

.custom-position-tip {
  margin: 8px 0 6px;
  font-size: 13px;
}

.custom-tag-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 8px;
  max-height: 260px;
  overflow-y: auto;
}

.chip-group-title {
  font-size: 12px;
  margin-top: 2px;
}

.resume-row select {
  width: auto;
  min-width: 220px;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 14px;
  background: #fff;
}

.quota-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 13px;
  background: #eef3ff;
  border: 1px solid #d5e0ff;
  color: var(--text);
}

.quota-billing-note {
  color: #047857;
}

.model-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  font-size: 14px;
}

.model-row select {
  padding: 6px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fff;
  color: var(--text);
}

.billing-hint-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 13px;
  background: #fff7e6;
  border: 1px solid #ffe3ad;
  color: var(--text);
}

.quota-banner.success {
  background: #ecfbf2;
  border-color: #b9ecd0;
}

.quota-banner.danger {
  background: #fff1f0;
  border-color: #ffd0cd;
}

.quota-config-btn {
  margin-left: auto;
  flex-shrink: 0;
}

/* ---------- 模式选择卡片 ---------- */
.mode-options {
  display: flex;
  gap: 14px;
  margin-top: 16px;
}

.mode-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  text-align: left;
  height: auto;
  padding: 16px 18px;
  background: #fff;
  color: var(--text);
  border: 1px solid var(--border);
  border-radius: 12px;
}

.mode-card:hover:not(:disabled) {
  border-color: var(--primary);
  background: #f6f8ff;
}

.mode-card:disabled {
  background: #fff;
}

.mode-name {
  font-size: 15px;
  font-weight: 600;
}

.mode-desc {
  font-size: 13px;
  white-space: normal;
  line-height: 1.6;
}

.back-btn {
  margin-top: 14px;
}

.status-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  margin-bottom: 12px;
  flex-shrink: 0;
}

.mode-badge {
  background: #e7f8ee;
  color: var(--success);
}

.progress {
  margin-left: auto;
}

.finish-btn {
  margin-left: 8px;
}

.chat-box {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.bubble-row {
  display: flex;
  animation: bubble-in 0.3s ease;
}

@keyframes bubble-in {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.bubble-row.followup .bubble {
  background: #fff7e6;
  border: 1px solid #ffe3ad;
}

.followup-tag {
  margin-bottom: 6px;
}

.stream-cursor {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 2px;
  background: var(--text);
  vertical-align: -2px;
  animation: cursor-blink 1s steps(1) infinite;
}

.thinking-text {
  margin-left: 8px;
  font-size: 13px;
  color: var(--text-light);
}

@keyframes cursor-blink {
  50% {
    opacity: 0;
  }
}

.stage-progress-wrap {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.stage-progress-track {
  flex: 1;
  height: 8px;
  background: #eef1f6;
  border-radius: 4px;
  overflow: hidden;
}

.stage-progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #6c8cff, #4f6ef7);
  border-radius: 4px;
  transition: width 0.6s;
}

.stage-progress-text {
  font-size: 12px;
  white-space: nowrap;
}

.mastered-btn,
.dontknow-btn {
  flex-shrink: 0;
  white-space: nowrap;
}

.mastered-btn:disabled,
.dontknow-btn:disabled {
  background: transparent;
  color: #c3c9d4;
}

.mastered-btn:hover:not(:disabled) {
  color: var(--success, #2e7d32);
}

.dontknow-btn:hover:not(:disabled) {
  color: var(--danger, #c62828);
}

/* ---------- 深度训练徽章与退出按钮 ---------- */
.deep-badge {
  background: #fff3e0;
  color: #b26a00;
  white-space: nowrap;
}

.deep-exit-btn {
  flex-shrink: 0;
  white-space: nowrap;
}

/* ---------- 训练模式「具体分析」小窗（锚定在得分徽章旁，向上展开） ---------- */
.analysis-anchor {
  position: relative;
  display: flex;
  align-items: center;
}

.analysis-btn {
  white-space: nowrap;
}

.analysis-popover {
  position: absolute;
  left: 0;
  bottom: calc(100% + 8px);
  z-index: 40;
  width: min(440px, 82vw);
  /* 高度上限由 JS 按会话窗口可见区域动态注入，保证弹窗完全包含在会话窗口内 */
  overflow-y: auto;
  padding: 14px 16px;
  text-align: left;
  box-shadow: 0 8px 28px rgba(31, 41, 55, 0.18);
  animation: bubble-in 0.2s ease;
}

/* 上方空间不足时翻转到锚点下方展示 */
.analysis-popover.below {
  bottom: auto;
  top: calc(100% + 8px);
}

.analysis-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
  /* 弹窗内部滚动时关闭钮保持可见 */
  position: sticky;
  top: -14px;
  background: var(--card, #fff);
  padding-top: 2px;
  padding-bottom: 8px;
  z-index: 1;
}

/* 关闭钮醒目化：主色实底白字，区别于普通幽灵按钮 */
.analysis-close {
  background: var(--primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  padding: 6px 16px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(31, 41, 55, 0.22);
}

.analysis-close:hover {
  filter: brightness(1.1);
}

.analysis-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  font-size: 13px;
}

.eval-title {
  font-weight: 600;
  margin-bottom: 4px;
}

.eval-section ul {
  margin: 0;
  padding-left: 18px;
}

.eval-section.good .eval-title {
  color: var(--success);
}

.eval-section.bad .eval-title {
  color: var(--danger, #e5484d);
}

.eval-section.improved blockquote {
  margin: 0;
  padding: 8px 12px;
  border-left: 3px solid var(--primary);
  background: #fff;
  border-radius: 0 6px 6px 0;
  color: var(--text-light);
}

.confirm-dialog {
  width: 380px;
  max-width: 90vw;
  text-align: center;
  padding: 28px;
}

.confirm-dialog h3 {
  margin-bottom: 10px;
}

.confirm-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
  margin-top: 20px;
}

.phase-banner {
  position: fixed;
  top: 40%;
  left: 50%;
  transform: translate(-50%, -50%);
  padding: 16px 36px;
  background: rgba(31, 41, 55, 0.85);
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  border-radius: 12px;
  z-index: 90;
  pointer-events: none;
}

.phase-fade-enter-active,
.phase-fade-leave-active {
  transition: opacity 0.5s ease;
}

.phase-fade-enter-from,
.phase-fade-leave-to {
  opacity: 0;
}

.bubble-row.user {
  justify-content: flex-end;
}

.bubble {
  max-width: 78%;
  padding: 10px 14px;
  border-radius: 12px;
}

.bubble-row.user .bubble {
  background: var(--primary);
  color: #fff;
}

.bubble-row.assistant .bubble {
  background: #f1f3f9;
}

.bubble-content {
  white-space: pre-wrap;
  word-break: break-word;
}

/* ---------- Markdown 渲染（面试官消息） ---------- */
.md {
  white-space: normal;
}

.md :deep(p) {
  margin: 0 0 8px;
}

.md :deep(p:last-child) {
  margin-bottom: 0;
}

.md :deep(strong) {
  font-weight: 600;
}

.md :deep(ul),
.md :deep(ol) {
  margin: 4px 0 8px;
  padding-left: 20px;
}

.md :deep(li) {
  margin-bottom: 2px;
}

.md :deep(code) {
  background: #e5eaf6;
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 13px;
}

.md :deep(pre) {
  background: #eef1f8;
  padding: 8px 12px;
  border-radius: 8px;
  overflow-x: auto;
  margin: 6px 0;
}

.md :deep(pre code) {
  background: transparent;
  padding: 0;
}

.md :deep(blockquote) {
  border-left: 3px solid #c8d2f5;
  padding-left: 10px;
  color: var(--text-light);
  margin: 6px 0;
}

.md :deep(h1),
.md :deep(h2),
.md :deep(h3) {
  font-size: 15px;
  font-weight: 600;
  margin: 8px 0 6px;
}

.md :deep(table) {
  border-collapse: collapse;
  margin: 6px 0;
}

.md :deep(th),
.md :deep(td) {
  border: 1px solid var(--border);
  padding: 4px 10px;
}

.score-line {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.comment {
  font-size: 12px;
}

/* ---------- 回答输入区：输入框 + 跳过/发送按钮同行右对齐，等高 ---------- */
.answer-row {
  display: flex;
  gap: 10px;
  margin-top: 12px;
  align-items: stretch;
  flex-shrink: 0;
}

.answer-row textarea {
  flex: 1;
  min-width: 0;
  resize: none;
}

.answer-actions {
  display: flex;
  gap: 10px;
  margin-left: auto;
  flex-shrink: 0;
}

.answer-row button {
  height: auto;
  min-height: 42px;
  white-space: nowrap;
}

/* ---------- 响应式三档 ---------- */
.interview-layout {
  display: block;
}

.interview-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  /* 输入框固定可见：主区限高视口，消息区内部滚动（vh 为旧浏览器回退） */
  height: calc(100vh - 230px);
  height: calc(100dvh - 230px);
  min-height: 400px;
}

.side-panel {
  display: none;
}

.status-fab {
  display: none;
}

.side-panel h3,
.bottom-sheet h3 {
  font-size: 15px;
  margin-bottom: 12px;
}

.side-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px dashed var(--border);
  font-size: 13px;
}

.side-row:last-of-type {
  border-bottom: none;
}

.finish-btn-side {
  width: 100%;
  margin-top: 14px;
}

/* 桌面档（1200px+）：对话区 + 右侧边栏 */
@media (min-width: 1200px) {
  .interview-layout {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 280px;
    gap: 16px;
    align-items: start;
  }

  .status-bar {
    display: none;
  }

  .side-panel {
    display: block;
    position: sticky;
    top: 84px;
  }

  .interview-main {
    height: calc(100vh - 180px);
    height: calc(100dvh - 180px);
  }
}

/* 手机档（<768px）：状态收入底部抽屉 */
@media (max-width: 767px) {
  .status-bar {
    display: none;
  }

  .mode-options {
    flex-direction: column;
  }

  .interview-main {
    height: calc(100vh - 250px);
    height: calc(100dvh - 250px);
    min-height: 320px;
  }

  .answer-row {
    flex-wrap: wrap;
  }

  .answer-row textarea {
    flex-basis: 100%;
  }

  /* 开局卡片：标题与岗位选择器纵向铺开，开始面试按钮铺满，避免窄屏挤压 */
  .start-head {
    flex-direction: column;
    align-items: stretch;
  }

  .position-picker {
    width: 100%;
  }

  .position-picker-btn {
    width: 100%;
    max-width: none;
  }

  .position-dropdown {
    left: 0;
    right: 0;
    max-width: none;
  }

  .start-row {
    flex-direction: column;
    align-items: stretch;
  }

  /* 状态悬浮钮移到右上方：底部留给作答区，软键盘弹起时也不会与发送按钮重叠 */
  .status-fab {
    display: flex;
    position: fixed;
    top: 78px;
    right: 12px;
    z-index: 50;
    align-items: center;
    gap: 6px;
    padding: 6px 12px;
    font-size: 13px;
    box-shadow: 0 6px 18px rgba(31, 41, 55, 0.2);
  }
}

.sheet-mask {
  position: fixed;
  inset: 0;
  background: rgba(31, 41, 55, 0.35);
  z-index: 80;
  display: flex;
  align-items: flex-end;
}

.bottom-sheet {
  width: 100%;
  border-radius: 16px 16px 0 0;
  padding: 16px 20px 24px;
  max-height: 70vh;
  overflow-y: auto;
}

.sheet-handle {
  width: 40px;
  height: 4px;
  border-radius: 2px;
  background: #d5dae5;
  margin: 0 auto 14px;
}

.sheet-enter-active,
.sheet-leave-active {
  transition: opacity 0.25s ease;
}

.sheet-enter-active .bottom-sheet,
.sheet-leave-active .bottom-sheet {
  transition: transform 0.25s ease;
}

.sheet-enter-from,
.sheet-leave-to {
  opacity: 0;
}

.sheet-enter-from .bottom-sheet,
.sheet-leave-to .bottom-sheet {
  transform: translateY(100%);
}
</style>
