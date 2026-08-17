<script setup>
import { ref } from 'vue'
import { knowledgeApi } from '../api'
import { notifyError } from '../utils/errors'
import { toast } from '../toast'

// 上传资料小窗：仅保留原资料库页的「上传我的资料」功能，上传成功后通知父级刷新
defineProps({
  // 已有自定义分组，供「归入分组」输入联想
  customCategories: {
    type: Array,
    default: () => []
  }
})

const emit = defineEmits(['close', 'uploaded'])

const fileInput = ref(null)
const selectedFile = ref(null)
const uploadCategory = ref('')
const uploading = ref(false)

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
    emit('uploaded')
    emit('close')
  } catch (e) {
    notifyError(e)
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div class="modal-mask" @click.self="$emit('close')">
    <div class="modal-card" role="dialog" aria-label="上传资料">
      <div class="modal-head">
        <h2>上传资料</h2>
        <button type="button" class="modal-close" aria-label="关闭" @click="$emit('close')">×</button>
      </div>

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
          list="upload-category-options"
          placeholder="归入分组（留空归入「自定义」）"
          maxlength="64"
          :disabled="uploading"
        />
        <datalist id="upload-category-options">
          <option v-for="name in customCategories" :key="name" :value="name" />
        </datalist>
        <button type="button" :disabled="uploading" @click="upload">
          {{ uploading ? '上传中…' : '上传入库' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-mask {
  position: fixed;
  inset: 0;
  z-index: 900;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(15, 23, 42, 0.45);
}

.modal-card {
  width: 100%;
  max-width: 720px;
  max-height: calc(100vh - 48px);
  overflow-y: auto;
  padding: 20px 22px;
  background: #fff;
  border-radius: 14px;
  box-shadow: 0 16px 48px rgba(15, 23, 42, 0.25);
}

.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.modal-head h2 {
  margin: 0;
}

.modal-close {
  padding: 0 6px;
  background: transparent;
  color: var(--text-light);
  font-size: 20px;
}

.modal-close:hover {
  color: var(--text);
  background: transparent;
}

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

.upload-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-top: 14px;
}

.pick-file-btn {
  background: var(--primary);
  color: #fff;
  font-weight: 600;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
