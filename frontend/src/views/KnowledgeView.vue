<script setup>
import { computed, onMounted, ref } from 'vue'
import { knowledgeApi } from '../api'
import { notifyError } from '../utils/errors'
import { toast } from '../toast'

// 资料库（任务 8）：官方分组全局共享，用户上传仅本人可见；自定义分组随上传自动建立
const officialCategories = ref([])
const customCategories = ref([])
const myItems = ref([])
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
    const [view, mine] = await Promise.all([knowledgeApi.categories(), knowledgeApi.mine()])
    officialCategories.value = view?.official || []
    customCategories.value = view?.custom || []
    myItems.value = mine || []
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
    toast(`官方题库导入完成：新增 ${summary.inserted} 题，已存在 ${summary.skipped} 题`)
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
      toast('仅支持 .md / .txt 格式的资料文件')
      event.target.value = ''
      selectedFile.value = null
      return
    }
    if (file.size > 1024 * 1024) {
      toast('文件大小不能超过 1MB')
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
    toast('请先选择要上传的文件')
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
    toast(`上传完成：识别 ${summary.parsed} 题，入库 ${summary.inserted} 题，重复跳过 ${summary.skipped} 题`)
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

async function removeItem(item) {
  if (!window.confirm(`确定删除「${item.question.slice(0, 30)}${item.question.length > 30 ? '…' : ''}」吗？`)) {
    return
  }
  try {
    await knowledgeApi.remove(item.id)
    toast('已删除')
    await loadAll()
  } catch (e) {
    notifyError(e)
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
      <p class="muted">
        支持 Markdown / TXT 文件（≤1MB），自动按问答对切分入库，仅本人可见。格式一：Q:/问: 为题面、A:/答:
        为参考答案；格式二：Markdown 标题为题面、标题间正文为答案。
      </p>
      <div class="upload-row">
        <button type="button" class="ghost" @click="pickFile">
          {{ selectedFile ? selectedFile.name : '选择文件（.md / .txt）' }}
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

    <!-- 我的上传 -->
    <div class="card">
      <div class="section-head">
        <h2>我的上传（{{ myItems.length }}）</h2>
      </div>
      <div v-if="customCategories.length" class="chip-list">
        <span v-for="name in customCategories" :key="name" class="badge success">{{ name }}</span>
      </div>
      <table v-if="myItems.length" class="mine-table">
        <thead>
          <tr>
            <th>题面</th>
            <th>分组</th>
            <th class="col-action">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in myItems" :key="item.id">
            <td class="col-question">{{ item.question }}</td>
            <td><span class="badge">{{ item.category }}</span></td>
            <td class="col-action">
              <button type="button" class="ghost danger-text" @click="removeItem(item)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else-if="!loading" class="muted">暂无上传资料；上传的题目可在开始面试时勾选使用。</p>
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

.upload-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-top: 12px;
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

.mine-table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 12px;
  font-size: 14px;
}

.mine-table th,
.mine-table td {
  text-align: left;
  padding: 8px 10px;
  border-bottom: 1px solid var(--border);
}

.mine-table .col-question {
  max-width: 480px;
}

.mine-table .col-action {
  width: 72px;
  white-space: nowrap;
}

.danger-text {
  color: var(--danger, #d4380d);
}

@media (max-width: 767px) {
  .upload-row {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
