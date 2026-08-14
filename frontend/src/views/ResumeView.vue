<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { resumeApi } from '../api'

const resumes = ref([])
const currentId = ref(null)
const loading = ref(true)
const saving = ref(false)
const parsing = ref(false)
const error = ref('')
const notice = ref('')

const form = reactive(emptyForm())
const rawText = ref('')

function emptyForm() {
  return {
    name: '',
    education: '',
    skills: '',
    internships: '',
    selfIntroduction: '',
    projects: []
  }
}

function emptyProject() {
  return {
    projectName: '',
    role: '',
    duration: '',
    description: '',
    techStack: '',
    highlights: '',
    challenges: ''
  }
}

const currentResume = computed(() => resumes.value.find((item) => item.id === currentId.value))

function formatTime(value) {
  if (!value) {
    return '—'
  }
  // 兼容 ISO 字符串与 Jackson LocalDateTime 数组两种格式
  if (Array.isArray(value)) {
    const [year, month, day, hour = 0, minute = 0] = value
    return new Date(year, month - 1, day, hour, minute).toLocaleString('zh-CN')
  }
  return new Date(value).toLocaleString('zh-CN')
}

onMounted(async () => {
  await refreshList()
  if (resumes.value.length > 0) {
    await loadResume(resumes.value[0].id)
  }
  loading.value = false
})

async function refreshList() {
  try {
    resumes.value = await resumeApi.list()
  } catch (e) {
    error.value = e.message
  }
}

async function loadResume(resumeId) {
  error.value = ''
  notice.value = ''
  try {
    const data = await resumeApi.detail(resumeId)
    currentId.value = data.id
    applyToForm(data)
  } catch (e) {
    error.value = e.message
  }
}

function applyToForm(data) {
  form.name = data.name || ''
  form.education = data.education || ''
  form.skills = data.skills || ''
  form.internships = data.internships || ''
  form.selfIntroduction = data.selfIntroduction || ''
  form.projects = (data.projects || []).map((p) => ({ ...emptyProject(), ...p }))
  rawText.value = data.rawText || ''
}

function createNew() {
  currentId.value = null
  Object.assign(form, emptyForm())
  rawText.value = ''
  error.value = ''
  notice.value = ''
}

function addProject() {
  form.projects.push(emptyProject())
}

function removeProject(index) {
  form.projects.splice(index, 1)
}

async function parseRawText() {
  if (!rawText.value.trim() || parsing.value) {
    return
  }
  parsing.value = true
  error.value = ''
  notice.value = ''
  try {
    const parsed = await resumeApi.parse(rawText.value)
    if (parsed.name) form.name = parsed.name
    if (parsed.education) form.education = parsed.education
    if (parsed.skills) form.skills = parsed.skills
    if (parsed.internships) form.internships = parsed.internships
    if (parsed.selfIntroduction) form.selfIntroduction = parsed.selfIntroduction
    if (Array.isArray(parsed.projects) && parsed.projects.length > 0) {
      form.projects = parsed.projects.map((p) => ({ ...emptyProject(), ...p }))
    }
    notice.value = '解析完成，已回填到下方表单，请核对后保存'
  } catch (e) {
    error.value = e.message
  } finally {
    parsing.value = false
  }
}

async function save() {
  if (saving.value) {
    return
  }
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const payload = {
      id: currentId.value,
      name: form.name.trim(),
      education: form.education.trim(),
      skills: form.skills.trim(),
      internships: form.internships.trim(),
      selfIntroduction: form.selfIntroduction.trim(),
      projects: form.projects,
      rawText: rawText.value.trim()
    }
    const data = await resumeApi.save(payload)
    currentId.value = data.id
    await refreshList()
    notice.value = '保存成功'
  } catch (e) {
    error.value = e.message
  } finally {
    saving.value = false
  }
}

async function removeResume(resumeId) {
  if (!window.confirm('确定删除这份简历吗？')) {
    return
  }
  try {
    await resumeApi.remove(resumeId)
    if (currentId.value === resumeId) {
      currentId.value = null
      Object.assign(form, emptyForm())
      rawText.value = ''
    }
    await refreshList()
    if (currentId.value == null && resumes.value.length > 0) {
      await loadResume(resumes.value[0].id)
    }
  } catch (e) {
    error.value = e.message
  }
}
</script>

<template>
  <div class="page">
    <h1 class="page-title">简历管理</h1>

    <div class="resume-layout">
      <!-- 左侧：简历列表 -->
      <div class="card list-card">
        <div class="list-header">
          <h2>我的简历</h2>
          <button class="secondary" @click="createNew">＋ 新建</button>
        </div>
        <p v-if="loading" class="empty">加载中…</p>
        <p v-else-if="resumes.length === 0" class="empty">暂无简历，可在右侧新建或粘贴简历原文</p>
        <ul v-else class="resume-list">
          <li
            v-for="item in resumes"
            :key="item.id"
            :class="['resume-item', { active: item.id === currentId }]"
            @click="loadResume(item.id)"
          >
            <div class="resume-item-main">
              <span class="resume-name">{{ item.name || '未命名候选人' }}</span>
              <span class="muted resume-time">{{ formatTime(item.updatedAt) }}</span>
            </div>
            <button class="ghost" title="删除" @click.stop="removeResume(item.id)">删除</button>
          </li>
        </ul>
      </div>

      <!-- 右侧：编辑区 -->
      <div class="edit-column">
        <!-- 纯文本粘贴 -->
        <div class="card">
          <h3>粘贴简历原文（可选）</h3>
          <p class="muted hint">直接粘贴简历全文，点击「AI 解析」自动回填到下方表单，解析结果请人工核对。</p>
          <textarea v-model="rawText" rows="6" placeholder="在此粘贴简历原文…"></textarea>
          <div class="action-row">
            <button class="secondary" :disabled="parsing || !rawText.trim()" @click="parseRawText">
              {{ parsing ? '解析中…' : 'AI 解析' }}
            </button>
          </div>
        </div>

        <!-- 结构化表单 -->
        <div class="card">
          <h3>{{ currentResume ? `编辑简历：${currentResume.name || '未命名候选人'}` : '新建简历' }}</h3>

          <div class="field">
            <span class="field-label">姓名</span>
            <input v-model="form.name" placeholder="候选人姓名" />
          </div>
          <div class="field">
            <span class="field-label">教育经历</span>
            <textarea v-model="form.education" rows="3" placeholder="学校、专业、学历、时间段等"></textarea>
          </div>
          <div class="field">
            <span class="field-label">技能列表</span>
            <textarea v-model="form.skills" rows="3" placeholder="掌握的技术栈，如：Java、Spring Boot、MySQL…"></textarea>
          </div>
          <div class="field">
            <span class="field-label">实习经历</span>
            <textarea v-model="form.internships" rows="3" placeholder="公司、岗位、时间段、主要工作内容等"></textarea>
          </div>
          <div class="field">
            <span class="field-label">自我介绍</span>
            <textarea v-model="form.selfIntroduction" rows="3" placeholder="一段简短的自我介绍"></textarea>
          </div>

          <!-- 项目经历 -->
          <div class="projects-section">
            <div class="list-header">
              <h4>项目经历（{{ form.projects.length }}）</h4>
              <button class="secondary" type="button" @click="addProject">＋ 添加项目</button>
            </div>
            <div v-for="(project, index) in form.projects" :key="index" class="card project-card">
              <div class="project-head">
                <span class="badge">项目 {{ index + 1 }}</span>
                <button class="ghost" type="button" @click="removeProject(index)">删除</button>
              </div>
              <div class="grid-2">
                <div class="field">
                  <span class="field-label">项目名称</span>
                  <input v-model="project.projectName" placeholder="项目名称" />
                </div>
                <div class="field">
                  <span class="field-label">角色</span>
                  <input v-model="project.role" placeholder="如：后端负责人" />
                </div>
                <div class="field">
                  <span class="field-label">时间段</span>
                  <input v-model="project.duration" placeholder="如：2025.03 - 2025.08" />
                </div>
                <div class="field">
                  <span class="field-label">技术栈</span>
                  <input v-model="project.techStack" placeholder="如：Spring Boot、Redis、MySQL" />
                </div>
              </div>
              <div class="field">
                <span class="field-label">项目描述</span>
                <textarea v-model="project.description" rows="3" placeholder="项目背景、功能概述"></textarea>
              </div>
              <div class="field">
                <span class="field-label">亮点 / 成果</span>
                <textarea v-model="project.highlights" rows="2" placeholder="量化成果、技术亮点"></textarea>
              </div>
              <div class="field">
                <span class="field-label">遇到的挑战</span>
                <textarea v-model="project.challenges" rows="2" placeholder="难点与解决思路"></textarea>
              </div>
            </div>
            <p v-if="form.projects.length === 0" class="muted hint">尚未添加项目，项目经历是面试「项目考察」环节出题的核心素材。</p>
          </div>

          <div class="action-row save-row">
            <button :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存简历' }}</button>
            <span v-if="notice" class="success-text">{{ notice }}</span>
          </div>
          <p v-if="error" class="error-text">{{ error }}</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.resume-layout {
  display: grid;
  grid-template-columns: 260px 1fr;
  gap: 16px;
  align-items: start;
}

.edit-column {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.resume-list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.resume-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  cursor: pointer;
  transition: border-color 0.2s;
}

.resume-item:hover {
  border-color: var(--primary);
}

.resume-item.active {
  border-color: var(--primary);
  background: #eef1ff;
}

.resume-item-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.resume-name {
  font-weight: 600;
}

.resume-time {
  font-size: 12px;
}

h3,
h4 {
  margin-bottom: 8px;
}

.hint {
  font-size: 13px;
  margin-bottom: 10px;
}

.field {
  margin-top: 12px;
}

.field-label {
  display: block;
  font-weight: 600;
  margin-bottom: 6px;
}

.projects-section {
  margin-top: 20px;
}

.project-card {
  margin-bottom: 12px;
  background: #fafbfe;
}

.project-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 12px;
}

.action-row {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.save-row {
  margin-top: 20px;
}

.success-text {
  color: var(--success);
  font-size: 13px;
}
</style>
