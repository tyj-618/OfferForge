<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { marked } from 'marked'
import { feedbackApi } from '../api'
import { classifyError } from '../utils/errors'
import { toast } from '../toast'

// 文档正文含 Markdown（标题/列表/表格/引用），与面试气泡保持一致的渲染配置
marked.use({ gfm: true, breaks: false })

// 内置 Markdown 资源（任务 13）：eager 打包进前端，后续新增文档只需在 src/docs 下添加 .md 并登记目录
const modules = import.meta.glob('../docs/*.md', { query: '?raw', import: 'default', eager: true })

// 侧边目录：全部为已交付文档（v1.0）；问题反馈页内附图文提交表单
const tocItems = [
  { id: 'guide', title: '功能引导', file: '../docs/guide.md' },
  { id: 'qa-tutorial', title: '面试问答教学', file: '../docs/qa-tutorial.md' },
  { id: 'changelog', title: '更新日志', file: '../docs/changelog.md' },
  { id: 'privacy', title: '隐私与安全', file: '../docs/privacy.md' },
  { id: 'feedback', title: '问题反馈', file: '../docs/feedback.md' }
]

const route = useRoute()
const router = useRouter()

const activeId = ref('')
const content = ref('')

function openDoc(id) {
  const item = tocItems.find((it) => it.id === id)
  if (!item) {
    return
  }
  activeId.value = id
  content.value = modules[item.file] || ''
  // 同步到 URL，支持刷新与分享直达
  if (route.query.doc !== id) {
    router.replace({ query: { doc: id } })
  }
  // 问题反馈页：进入即拉取本人历史反馈（提交后可回看）
  if (id === 'feedback') {
    loadMyFeedback()
  }
}

// 渲染后的 HTML：marked 默认关闭 html，原始 HTML 会被转义，v-html 仅渲染自产标签
const renderedHtml = computed(() => (content.value ? marked.parse(content.value) : ''))

// URL 携带 doc 参数直达对应文档；缺省打开第一篇
const initial = tocItems.some((it) => it.id === route.query.doc)
  ? route.query.doc
  : tocItems[0].id
openDoc(initial)

// 前进/后退切换文档时保持内容同步
watch(
  () => route.query.doc,
  (doc) => {
    if (route.name !== 'docs') {
      return
    }
    const target = tocItems.some((it) => it.id === doc) ? doc : tocItems[0].id
    if (target !== activeId.value) {
      openDoc(target)
    }
  }
)

// ---------- 问题反馈：图文提交 ----------
const MAX_IMAGES = 3
const MAX_IMAGE_BYTES = 1024 * 1024

const fbType = ref('BUG')
const fbContent = ref('')
const fbImages = ref([])
const fbSubmitting = ref(false)
const myFeedbacks = ref([])
const myFeedbacksLoading = ref(false)

const typeLabels = { BUG: '问题缺陷', SUGGESTION: '功能建议', OTHER: '其他' }

// 选图转 data URL：最多 3 张、单张 ≤1MB，超限即时提示不入队
function onPickImages(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  for (const file of files) {
    if (fbImages.value.length >= MAX_IMAGES) {
      toast.error(`最多上传 ${MAX_IMAGES} 张图片`)
      break
    }
    if (!file.type.startsWith('image/')) {
      toast.error('仅支持图片文件')
      continue
    }
    if (file.size > MAX_IMAGE_BYTES) {
      toast.error(`图片「${file.name}」超过 1MB 上限`)
      continue
    }
    const reader = new FileReader()
    reader.onload = () => {
      if (fbImages.value.length < MAX_IMAGES) {
        fbImages.value.push({ name: file.name, dataUrl: reader.result })
      }
    }
    reader.readAsDataURL(file)
  }
}

function removeImage(index) {
  fbImages.value.splice(index, 1)
}

async function submitFeedback() {
  if (fbSubmitting.value) {
    return
  }
  if (!fbContent.value.trim()) {
    toast.error('请填写反馈内容')
    return
  }
  fbSubmitting.value = true
  try {
    await feedbackApi.submit(
      fbType.value,
      fbContent.value.trim(),
      fbImages.value.map((item) => item.dataUrl)
    )
    toast.success('反馈提交成功，感谢你的意见')
    fbContent.value = ''
    fbImages.value = []
    await loadMyFeedback()
  } catch (e) {
    toast.error(classifyError(e).message)
  } finally {
    fbSubmitting.value = false
  }
}

async function loadMyFeedback() {
  myFeedbacksLoading.value = true
  try {
    myFeedbacks.value = await feedbackApi.mine()
  } catch {
    // 历史反馈加载失败不阻断文档阅读，保持空态即可
    myFeedbacks.value = []
  } finally {
    myFeedbacksLoading.value = false
  }
}
</script>

<template>
  <div class="page docs-page">
    <h1 class="page-title">文档</h1>
    <div class="docs-layout">
      <aside class="docs-toc card">
        <div class="toc-title">目录</div>
        <ul>
          <li
            v-for="item in tocItems"
            :key="item.id"
            :class="{ 'is-active': item.id === activeId }"
            @click="openDoc(item.id)"
          >
            {{ item.title }}
          </li>
        </ul>
      </aside>
      <article class="docs-content card">
        <!-- eslint-disable-next-line vue/no-v-html -->
        <div class="doc-body" v-html="renderedHtml"></div>

        <!-- 问题反馈提交区：仅反馈页展示，图文提交直达管理台 -->
        <div v-if="activeId === 'feedback'" class="feedback-box">
          <h2 class="fb-title">提交反馈</h2>
          <div class="fb-types">
            <label v-for="(label, value) in typeLabels" :key="value" class="fb-type">
              <input v-model="fbType" type="radio" :value="value" />
              {{ label }}
            </label>
          </div>
          <textarea
            v-model="fbContent"
            class="fb-content"
            rows="5"
            maxlength="2000"
            placeholder="请描述你遇到的问题或建议（可附操作步骤与预期表现，2000 字以内）"
          ></textarea>
          <div class="fb-images">
            <div v-for="(image, index) in fbImages" :key="index" class="fb-image-item">
              <img :src="image.dataUrl" :alt="image.name" />
              <button type="button" class="fb-image-remove" @click="removeImage(index)">✕</button>
            </div>
            <label v-if="fbImages.length < 3" class="fb-image-add">
              <input type="file" accept="image/*" multiple hidden @change="onPickImages" />
              ＋ 添加截图（≤1MB）
            </label>
          </div>
          <button class="primary" :disabled="fbSubmitting" @click="submitFeedback">
            {{ fbSubmitting ? '提交中…' : '提交反馈' }}
          </button>

          <div class="fb-mine">
            <h3>我的反馈</h3>
            <p v-if="myFeedbacksLoading" class="muted">加载中…</p>
            <p v-else-if="!myFeedbacks.length" class="muted">暂无提交记录</p>
            <div v-for="item in myFeedbacks" v-else :key="item.id" class="fb-mine-item">
              <div class="fb-mine-head">
                <span class="fb-mine-type">{{ typeLabels[item.type] || item.type }}</span>
                <span class="muted">{{ item.createdAt }}</span>
              </div>
              <p class="fb-mine-content">{{ item.content }}</p>
              <div v-if="item.images && item.images.length" class="fb-mine-images">
                <img v-for="(src, index) in item.images" :key="index" :src="src" alt="反馈截图" />
              </div>
            </div>
          </div>
        </div>
      </article>
    </div>
  </div>
</template>

<style scoped>
.docs-layout {
  display: flex;
  gap: 20px;
  align-items: flex-start;
}

.docs-toc {
  width: 200px;
  flex-shrink: 0;
  padding: 16px 12px;
}

.toc-title {
  font-weight: 700;
  padding: 0 10px 10px;
  border-bottom: 1px solid var(--border);
  margin-bottom: 8px;
}

.docs-toc li {
  list-style: none;
  padding: 9px 10px;
  border-radius: 8px;
  cursor: pointer;
  color: var(--text-light);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.docs-toc li:hover {
  background: #f0f3fb;
}

.docs-toc li.is-active {
  background: #eef1ff;
  color: var(--primary);
  font-weight: 600;
}

/* 问题反馈提交区 */
.feedback-box {
  margin-top: 28px;
  padding-top: 20px;
  border-top: 1px solid var(--border);
}

.fb-title {
  font-size: 17px;
  margin-bottom: 12px;
}

.fb-types {
  display: flex;
  gap: 18px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.fb-type {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  cursor: pointer;
}

.fb-content {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 14px;
  resize: vertical;
  box-sizing: border-box;
}

.fb-images {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin: 12px 0;
}

.fb-image-item {
  position: relative;
  width: 88px;
  height: 88px;
}

.fb-image-item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border: 1px solid var(--border);
  border-radius: 8px;
}

.fb-image-remove {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 20px;
  height: 20px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: #dc2626;
  color: #fff;
  font-size: 11px;
  line-height: 20px;
  cursor: pointer;
}

.fb-image-add {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 88px;
  height: 88px;
  border: 1px dashed var(--border);
  border-radius: 8px;
  font-size: 12px;
  color: var(--text-light);
  cursor: pointer;
  text-align: center;
}

.fb-image-add:hover {
  border-color: var(--primary);
  color: var(--primary);
}

.fb-mine {
  margin-top: 26px;
}

.fb-mine h3 {
  font-size: 15px;
  margin-bottom: 10px;
}

.fb-mine-item {
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  margin-bottom: 10px;
}

.fb-mine-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 13px;
  margin-bottom: 4px;
}

.fb-mine-type {
  color: var(--primary);
  font-weight: 600;
}

.fb-mine-content {
  font-size: 14px;
  white-space: pre-wrap;
  word-break: break-all;
}

.fb-mine-images {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 8px;
}

.fb-mine-images img {
  width: 88px;
  height: 88px;
  object-fit: cover;
  border: 1px solid var(--border);
  border-radius: 8px;
  cursor: zoom-in;
}

.docs-content {
  flex: 1;
  min-width: 0;
}

/* v-html 渲染内容需穿透 scoped 样式 */
.doc-body :deep(h1) {
  font-size: 22px;
  margin-bottom: 16px;
}

.doc-body :deep(h2) {
  font-size: 17px;
  margin: 24px 0 10px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--border);
}

.doc-body :deep(h3) {
  font-size: 15px;
  margin: 18px 0 8px;
}

.doc-body :deep(p) {
  margin: 8px 0;
}

.doc-body :deep(ul),
.doc-body :deep(ol) {
  padding-left: 22px;
  margin: 8px 0;
}

.doc-body :deep(li) {
  margin: 4px 0;
}

.doc-body :deep(blockquote) {
  border-left: 3px solid var(--primary);
  background: #f6f8ff;
  padding: 8px 14px;
  margin: 10px 0;
  border-radius: 0 8px 8px 0;
  color: var(--text-light);
}

.doc-body :deep(table) {
  border-collapse: collapse;
  margin: 12px 0;
  width: 100%;
}

.doc-body :deep(th),
.doc-body :deep(td) {
  border: 1px solid var(--border);
  padding: 7px 12px;
  text-align: left;
}

.doc-body :deep(th) {
  background: #f0f3fb;
}

.doc-body :deep(code) {
  background: #f0f3fb;
  border-radius: 4px;
  padding: 1px 6px;
  font-size: 13px;
}

@media (max-width: 767px) {
  .docs-layout {
    flex-direction: column;
  }

  .docs-toc {
    width: 100%;
  }
}
</style>
