<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { resumeApi } from '../api'
import { classifyError, notifyError } from '../utils/errors'

const resumes = ref([])
const currentId = ref(null)
const detail = ref(null)
const isEditing = ref(false)
const loading = ref(true)
const saving = ref(false)
const parsing = ref(false)
const error = ref('')
const notice = ref('')

const form = reactive(emptyForm())
const rawText = ref('')
const nameInput = ref(null)

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
const isNewResume = computed(() => currentId.value === null)
// 预览内容是否全空（新建未填写或空简历）
const previewEmpty = computed(() => {
  const d = detail.value
  if (!d) {
    return true
  }
  return !d.education && !d.skills && !d.internships && !d.selfIntroduction && !(d.projects && d.projects.length)
})

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
    error.value = classifyError(e).message
  }
}

// 加载详情并进入只读预览模式（加载完成即退出编辑态）
async function loadResume(resumeId) {
  error.value = ''
  notice.value = ''
  try {
    const data = await resumeApi.detail(resumeId)
    currentId.value = data.id
    detail.value = data
    applyToForm(data)
    isEditing.value = false
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

function resetEditor() {
  currentId.value = null
  detail.value = null
  Object.assign(form, emptyForm())
  rawText.value = ''
  error.value = ''
  notice.value = ''
}

// 新建简历：清空编辑器并直接进入编辑模式
function createNew() {
  resetEditor()
  isEditing.value = true
  nameInput.value?.focus()
}

function startEdit() {
  error.value = ''
  notice.value = ''
  isEditing.value = true
}

// 取消编辑：放弃表单修改回到只读预览；新建未保存则回到列表首条或空状态
function cancelEdit() {
  if (currentId.value != null) {
    loadResume(currentId.value)
  } else if (resumes.value.length > 0) {
    loadResume(resumes.value[0].id)
  } else {
    resetEditor()
    isEditing.value = false
  }
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
    notifyError(e, parseRawText)
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
    // 保存成功后重新拉取详情并自动切回只读预览
    await loadResume(data.id)
    notice.value = '保存成功'
  } catch (e) {
    notifyError(e, save)
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
    await refreshList()
    if (currentId.value === resumeId) {
      if (resumes.value.length > 0) {
        await loadResume(resumes.value[0].id)
      } else {
        resetEditor()
      }
    }
  } catch (e) {
    notifyError(e, () => removeResume(resumeId))
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
        <p v-else-if="resumes.length === 0" class="empty">暂无简历，点击「＋ 新建」创建第一份简历</p>
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

      <!-- 右侧：只读预览 / 编辑 -->
      <div class="edit-column">
        <!-- ========== 只读预览模式 ========== -->
        <div v-if="!isEditing && detail" class="card preview-card">
          <div class="preview-header">
            <div>
              <h3 class="preview-name">{{ detail.name || '未命名候选人' }}</h3>
              <p class="muted preview-meta">
                <span>最近更新：{{ formatTime(detail.updatedAt) }}</span>
                <span v-if="notice" class="success-text">{{ notice }}</span>
              </p>
            </div>
            <div class="preview-actions">
              <button class="secondary" @click="startEdit">编辑</button>
              <button class="ghost" @click="removeResume(detail.id)">删除</button>
            </div>
          </div>

          <p v-if="error" class="error-text">{{ error }}</p>
          <p v-if="previewEmpty" class="empty">这份简历还没有内容，点击「编辑」开始填写。</p>

          <section v-if="detail.education" class="preview-section">
            <h4>教育经历</h4>
            <p class="preview-text">{{ detail.education }}</p>
          </section>
          <section v-if="detail.skills" class="preview-section">
            <h4>技能列表</h4>
            <p class="preview-text">{{ detail.skills }}</p>
          </section>
          <section v-if="detail.internships" class="preview-section">
            <h4>实习经历</h4>
            <p class="preview-text">{{ detail.internships }}</p>
          </section>
          <section v-if="detail.selfIntroduction" class="preview-section">
            <h4>自我介绍</h4>
            <p class="preview-text">{{ detail.selfIntroduction }}</p>
          </section>
          <section v-if="detail.projects && detail.projects.length" class="preview-section">
            <h4>项目经历</h4>
            <div v-for="(project, index) in detail.projects" :key="index" class="preview-project">
              <div class="preview-project-head">
                <strong>{{ project.projectName || '未命名项目' }}</strong>
                <span v-if="project.role" class="badge">{{ project.role }}</span>
                <span v-if="project.duration" class="muted">{{ project.duration }}</span>
              </div>
              <p v-if="project.techStack" class="muted">技术栈：{{ project.techStack }}</p>
              <p v-if="project.description" class="preview-text">{{ project.description }}</p>
              <p v-if="project.highlights" class="preview-text"><strong>亮点 / 成果：</strong>{{ project.highlights }}</p>
              <p v-if="project.challenges" class="preview-text"><strong>挑战：</strong>{{ project.challenges }}</p>
            </div>
          </section>
        </div>

        <!-- 无简历时的占位 -->
        <div v-else-if="!isEditing" class="card">
          <p class="empty">暂无简历，点击左上角「＋ 新建」创建第一份简历</p>
        </div>

        <!-- ========== 编辑模式 ========== -->
        <template v-else>
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
            <h3>{{ isNewResume ? '新建简历' : `编辑简历：${currentResume ? currentResume.name || '未命名候选人' : ''}` }}</h3>

            <div class="field">
              <span class="field-label">姓名</span>
              <input ref="nameInput" v-model="form.name" placeholder="候选人姓名" />
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
              <button class="secondary" :disabled="saving" @click="cancelEdit">取消</button>
              <button :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存简历' }}</button>
              <span v-if="notice" class="success-text">{{ notice }}</span>
            </div>
            <p v-if="error" class="error-text">{{ error }}</p>
          </div>
        </template>
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

/* ===== 只读预览模式 ===== */
.preview-card {
  line-height: 1.7;
}

.preview-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.preview-name {
  font-size: 20px;
  margin: 0;
}

.preview-meta {
  display: flex;
  gap: 12px;
  font-size: 12px;
  margin-top: 6px;
}

.preview-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.preview-section {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--border);
}

.preview-section h4 {
  color: var(--primary);
  margin-bottom: 6px;
}

.preview-text {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 14px;
}

.preview-project {
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fafbfe;
  margin-bottom: 10px;
}

.preview-project-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 4px;
}

.preview-project p {
  margin-top: 4px;
  font-size: 14px;
}

/* 平板/手机档：列表与编辑区改为单列 */
@media (max-width: 1199px) {
  .resume-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 767px) {
  .grid-2 {
    grid-template-columns: 1fr;
  }
}
</style>
