<script setup>
import { computed, onMounted, ref } from 'vue'
import { knowledgeApi } from '../api'
import { notifyError } from '../utils/errors'
import { toast } from '../toast'

// 资料库（导入/上传入口）：列表浏览与删除已迁至资源库页，本页不再铺开已上传条目
const officialCategories = ref([])
const customCategories = ref([])
const loading = ref(false)
const importing = ref(false)
const uploading = ref(false)

const fileInput = ref(null)
const uploadCategory = ref('')
const selectedFile = ref(null)

const customCategoryOptions = computed(() => customCategories.value)

async function loadAll() {
  loading.value = true
  try {
    const view = await knowledgeApi.categories()
    officialCategories.value = view?.official || []
    customCategories.value = view?.custom || []
  } catch (e) {
    notifyError(e)
  } finally {
    loading.value = false
  }
}

// 导入官方题库：幂等，已存在的题自动跳过
async function importOfficial() {
  importing.value = true
  try {
    const summary = await knowledgeApi.importBuiltin()
    toast.success(`官方题库导入完成：新增 ${summary.inserted} 题，已存在 ${summary.skipped} 题`)
    await loadAll()
  } catch (e) {
    notifyError(e)
  } finally {
    importing.value = false
  }
}

function pickFile() {
  fileInput.value?.click()
}

function onFileChange(event) {
  const file = event.target.files?.[0] || null
  if (file) {
    const name = file.name.toLowerCase()
    if (!name.endsWith('.md') && !name.endsWith('.txt')) {
      toast.info('仅支持 .md / .txt 格式的资料文件')
      event.target.value = ''
      selectedFile.value = null
      return
    }
    if (file.size > 1024 * 1024) {
      toast.info('文件大小不能超过 1MB')
      event.target.value = ''
      selectedFile.value = null
      return
    }
  }
  selectedFile.value = file
}

// 上传资料：支持 Q:/A:（问:/答:）标记或 Markdown 标题两种问答格式
async function upload() {
  if (!selectedFile.value) {
    toast.info('请先选择要上传的文件')
    return
  }
  uploading.value = true
  try {
    const formData = new FormData()
    formData.append('file', selectedFile.value)
    if (uploadCategory.value.trim()) {
      formData.append('category', uploadCategory.value.trim())
    }
    const summary = await knowledgeApi.upload(formData)
    toast.success(`上传完成：识别 ${summary.parsed} 题，入库 ${summary.inserted} 题，重复跳过 ${summary.skipped} 题`)
    uploadCategory.value = ''
    selectedFile.value = null
    if (fileInput.value) {
      fileInput.value.value = ''
    }
    await loadAll()
  } catch (e) {
    notifyError(e)
  } finally {
    uploading.value = false
  }
}

onMounted(loadAll)
</script>

<template>
  <div class="page">
    <h1 class="page-title">资料库</h1>

    <!-- 官方分组 -->
    <div class="card">
      <div class="section-head">
        <h2>官方分组</h2>
        <button type="button" class="ghost" :disabled="importing" @click="importOfficial">
          {{ importing ? '导入中…' : '导入/更新官方题库' }}
        </button>
      </div>
      <p class="muted">官方题库全局共享，面试与快捷提问默认使用；点击「导入/更新」拉取最新内置题目。</p>
      <div v-if="officialCategories.length" class="chip-list">
        <span v-for="name in officialCategories" :key="name" class="badge">{{ name }}</span>
      </div>
      <p v-else-if="!loading" class="muted">官方题库尚未导入，点击右上角按钮一键导入。</p>
    </div>

    <!-- 上传资料 -->
    <div class="card">
      <h2>上传我的资料</h2>
      <p class="muted">支持 Markdown / TXT 文件（≤1MB），自动按问答对切分入库，仅本人可见。支持以下两种格式：</p>
      <div class="format-grid">
        <div class="format-card">
          <div class="format-title">格式一：Q:/A: 标记</div>
          <p class="muted">Q: 或 问: 开头的行为题面，A: 或 答: 开头的行为参考答案。</p>
          <pre class="format-example">Q: TCP 三次握手的作用？
A: 建立可靠连接并同步双方初始序号…

问: Redis 为什么快？
答: 基于内存操作，单线程避免锁竞争…</pre>
        </div>
        <div class="format-card">
          <div class="format-title">格式二：Markdown 标题</div>
          <p class="muted">Markdown 标题（# 开头）为题面，标题之间的正文为答案。</p>
          <pre class="format-example"># TCP 三次握手的作用？

建立可靠连接并同步双方初始序号…

# Redis 为什么快？

基于内存操作，单线程避免锁竞争…</pre>
        </div>
      </div>
      <div class="upload-row">
        <button type="button" class="pick-file-btn" @click="pickFile">
          📄 {{ selectedFile ? selectedFile.name : '选择文件（.md / .txt）' }}
        </button>
        <input
          ref="fileInput"
          type="file"
          accept=".md,.txt"
          class="file-hidden"
          @change="onFileChange"
        />
        <input
          v-model="uploadCategory"
          class="category-input"
          list="custom-category-options"
          placeholder="归入分组（留空归入「自定义」）"
          maxlength="64"
          :disabled="uploading"
        />
        <datalist id="custom-category-options">
          <option v-for="name in customCategoryOptions" :key="name" :value="name" />
        </datalist>
        <button type="button" :disabled="uploading" @click="upload">
          {{ uploading ? '上传中…' : '上传入库' }}
        </button>
      </div>
    </div>

    <!-- 已上传资料的浏览/删除统一在资源库页，本页不再铺开展示 -->
    <div class="card">
      <div class="section-head">
        <h2>查看与管理资料</h2>
        <RouterLink class="ghost" to="/library">前往资源库</RouterLink>
      </div>
      <p class="muted">官方题库与已上传资料均可在资源库页按分组筛选、搜索关键词、展开查看答案；自己上传的资料支持批量删除。</p>
    </div>
  </div>
</template>

<style scoped>
.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.section-head h2 {
  margin: 0;
}

.chip-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

/* 选择文件：主色调实心按钮，比 ghost 更醒目 */
.pick-file-btn {
  background: var(--primary);
  color: #fff;
  font-weight: 600;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.upload-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-top: 12px;
}

/* 上传格式说明：两种格式并列卡片，各自带示例，避免一段式文字难以分辨 */
.format-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-top: 10px;
}

.format-card {
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 12px 14px;
  background: #fafbff;
}

.format-title {
  font-weight: 600;
  margin-bottom: 6px;
}

.format-card p {
  margin: 0 0 8px;
  font-size: 13px;
}

.format-example {
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: #fff;
  border: 1px solid var(--border);
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--text-light);
}

.file-hidden {
  display: none;
}

.category-input {
  flex: 1;
  min-width: 200px;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
}

@media (max-width: 767px) {
  .upload-row {
    flex-direction: column;
    align-items: stretch;
  }

  .format-grid {
    grid-template-columns: 1fr;
  }
}
</style>
