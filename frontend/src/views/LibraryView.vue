<script setup>
import { computed, onMounted, ref } from 'vue'
import { knowledgeApi } from '../api'
import { notifyError } from '../utils/errors'
import { toast } from '../toast'

// 资源库：官方/我的双标签浏览，分组筛选 + 关键词搜索；仅「我的」可勾选批量删除
const tab = ref('official') // 'official' | 'mine'
const officialItems = ref([])
const myItems = ref([])
const activeCategory = ref('')
const keyword = ref('')
const expandedId = ref(null)
const selectedIds = ref([])
const loading = ref(false)
const deleting = ref(false)

// 迁移状态：当前打开迁移面板的条目、目标分组（已有标签选择或新建标签输入）
const moveTargetId = ref(null)
const moveCategory = ref('')
const moveNewName = ref('')
const moving = ref(false)
const categoryOptions = ref([])

const currentItems = computed(() => (tab.value === 'official' ? officialItems.value : myItems.value))

// 当前标签下的分组列表：按条目实际分组去重，保持首次出现顺序
const categoryList = computed(() => {
  const seen = new Set()
  const list = []
  for (const item of currentItems.value) {
    if (!seen.has(item.category)) {
      seen.add(item.category)
      list.push(item.category)
    }
  }
  return list
})

// 前端筛选：分组 + 关键词（命中题面或答案）
const filteredItems = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  return currentItems.value.filter((item) => {
    if (activeCategory.value && item.category !== activeCategory.value) {
      return false
    }
    if (!query) {
      return true
    }
    return (
      item.question.toLowerCase().includes(query) ||
      (item.answer || '').toLowerCase().includes(query)
    )
  })
})

const allVisibleSelected = computed(
  () => filteredItems.value.length > 0 && filteredItems.value.every((item) => selectedIds.value.includes(item.id))
)

async function loadAll() {
  loading.value = true
  try {
    const [official, mine] = await Promise.all([knowledgeApi.official(), knowledgeApi.mine()])
    officialItems.value = official || []
    myItems.value = mine || []
  } catch (e) {
    notifyError(e)
  } finally {
    loading.value = false
  }
}

// 迁移目标标签候选：官方分组 + 本人自定义分组
async function loadCategoryOptions() {
  try {
    const view = await knowledgeApi.categories()
    categoryOptions.value = [...(view?.official || []), ...(view?.custom || [])]
  } catch (e) {
    notifyError(e)
  }
}

function switchTab(nextTab) {
  if (tab.value === nextTab) {
    return
  }
  tab.value = nextTab
  // 切标签时重置筛选、勾选与迁移面板，避免跨标签串用
  activeCategory.value = ''
  keyword.value = ''
  expandedId.value = null
  selectedIds.value = []
  moveTargetId.value = null
}

function pickCategory(category) {
  activeCategory.value = activeCategory.value === category ? '' : category
}

function toggleExpand(item) {
  expandedId.value = expandedId.value === item.id ? null : item.id
}

function toggleSelect(id) {
  const index = selectedIds.value.indexOf(id)
  if (index >= 0) {
    selectedIds.value.splice(index, 1)
  } else {
    selectedIds.value.push(id)
  }
}

function toggleSelectAll() {
  if (allVisibleSelected.value) {
    const visibleIds = new Set(filteredItems.value.map((item) => item.id))
    selectedIds.value = selectedIds.value.filter((id) => !visibleIds.has(id))
  } else {
    const merged = new Set(selectedIds.value)
    filteredItems.value.forEach((item) => merged.add(item.id))
    selectedIds.value = [...merged]
  }
}

async function removeOne(item) {
  if (!window.confirm(`确定删除「${item.question.slice(0, 30)}${item.question.length > 30 ? '…' : ''}」吗？`)) {
    return
  }
  try {
    await knowledgeApi.remove(item.id)
    toast.success('已删除')
    await loadAll()
  } catch (e) {
    notifyError(e)
  }
}

async function batchRemove() {
  if (!selectedIds.value.length) {
    return
  }
  if (!window.confirm(`确定删除选中的 ${selectedIds.value.length} 条资料吗？`)) {
    return
  }
  deleting.value = true
  try {
    const result = await knowledgeApi.batchRemove(selectedIds.value)
    toast.success(`已删除 ${result.deleted} 条资料`)
    selectedIds.value = []
    await loadAll()
  } catch (e) {
    notifyError(e)
  } finally {
    deleting.value = false
  }
}

// 打开/关闭迁移面板：默认选中当前分组，新建标签输入优先于下拉选择
function openMove(item) {
  if (moveTargetId.value === item.id) {
    moveTargetId.value = null
    return
  }
  moveTargetId.value = item.id
  moveCategory.value = item.category
  moveNewName.value = ''
}

async function submitMove(item) {
  const target = moveNewName.value.trim() || moveCategory.value
  if (!target) {
    toast.info('请选择或输入目标标签')
    return
  }
  if (target === item.category) {
    toast.info('该资料已在目标标签中')
    moveTargetId.value = null
    return
  }
  moving.value = true
  try {
    await knowledgeApi.updateCategory(item.id, target)
    toast.success(`已迁移到「${target}」`)
    moveTargetId.value = null
    await Promise.all([loadAll(), loadCategoryOptions()])
  } catch (e) {
    notifyError(e)
  } finally {
    moving.value = false
  }
}

onMounted(() => {
  loadAll()
  loadCategoryOptions()
})
</script>

<template>
  <div class="page">
    <h1 class="page-title">资源库</h1>

    <!-- 标签：官方资料 / 我的资料 -->
    <div class="tabs" role="tablist">
      <button
        type="button"
        role="tab"
        :class="['tab', { active: tab === 'official' }]"
        @click="switchTab('official')"
      >
        官方资料（{{ officialItems.length }}）
      </button>
      <button
        type="button"
        role="tab"
        :class="['tab', { active: tab === 'mine' }]"
        @click="switchTab('mine')"
      >
        我的资料（{{ myItems.length }}）
      </button>
    </div>

    <div class="card">
      <!-- 筛选区：分组 chips + 关键词搜索 -->
      <div class="filter-bar">
        <div class="chip-list">
          <button
            type="button"
            :class="['chip', { active: !activeCategory }]"
            @click="activeCategory = ''"
          >
            全部
          </button>
          <button
            v-for="name in categoryList"
            :key="name"
            type="button"
            :class="['chip', { active: activeCategory === name }]"
            @click="pickCategory(name)"
          >
            {{ name }}
          </button>
        </div>
        <input
          v-model="keyword"
          class="search-input"
          type="search"
          placeholder="搜索题面或答案关键词…"
        />
      </div>

      <!-- 我的资料：批量操作栏 -->
      <div v-if="tab === 'mine'" class="batch-bar">
        <label class="select-all">
          <input
            type="checkbox"
            :checked="allVisibleSelected"
            :disabled="!filteredItems.length"
            @change="toggleSelectAll"
          />
          全选当前筛选（{{ filteredItems.length }}）
        </label>
        <span class="muted">已选 {{ selectedIds.length }} 条</span>
        <button
          type="button"
          class="ghost danger-text"
          :disabled="!selectedIds.length || deleting"
          @click="batchRemove"
        >
          {{ deleting ? '删除中…' : `批量删除（${selectedIds.length}）` }}
        </button>
      </div>

      <!-- 条目列表：点击题面展开答案 -->
      <div v-if="loading" class="muted">加载中…</div>
      <div v-else-if="!filteredItems.length" class="muted empty-tip">
        {{ currentItems.length ? '没有符合筛选条件的资料' : (tab === 'official' ? '官方题库尚未导入，请到资料库页一键导入' : '暂无上传资料，请到资料库页上传') }}
      </div>
      <div v-else class="item-list">
        <div v-for="item in filteredItems" :key="item.id" class="item">
          <div class="item-head" @click="toggleExpand(item)">
            <input
              v-if="tab === 'mine'"
              type="checkbox"
              class="item-check"
              :checked="selectedIds.includes(item.id)"
              @click.stop
              @change="toggleSelect(item.id)"
            />
            <span class="item-question">{{ item.question }}</span>
            <span class="badges">
              <span class="badge">{{ item.category }}</span>
              <span v-if="item.difficulty" class="badge outline">{{ item.difficulty }}</span>
            </span>
            <span class="expand-icon">{{ expandedId === item.id ? '▲' : '▼' }}</span>
          </div>
          <div v-if="expandedId === item.id" class="item-body">
            <pre class="answer-block">{{ item.answer }}</pre>
            <div v-if="tab === 'mine'" class="item-actions">
              <button type="button" class="ghost" @click="openMove(item)">
                {{ moveTargetId === item.id ? '收起迁移' : '迁移' }}
              </button>
              <button type="button" class="ghost danger-text" @click="removeOne(item)">删除</button>
            </div>
            <!-- 迁移面板：选已有标签或输入新建标签（新建优先） -->
            <div v-if="tab === 'mine' && moveTargetId === item.id" class="move-panel">
              <span class="move-label">迁移到标签：</span>
              <select v-model="moveCategory" class="move-select" :disabled="moving">
                <option v-for="name in categoryOptions" :key="name" :value="name">{{ name }}</option>
              </select>
              <input
                v-model="moveNewName"
                class="move-input"
                maxlength="64"
                placeholder="或输入新标签名（优先生效）"
                :disabled="moving"
              />
              <button type="button" :disabled="moving" @click="submitMove(item)">
                {{ moving ? '迁移中…' : '确认迁移' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
}

.tab {
  padding: 8px 18px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: #fff;
  color: var(--text-light);
  font-weight: 600;
}

.tab.active {
  background: var(--primary);
  border-color: var(--primary);
  color: #fff;
}

.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.chip-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.chip {
  padding: 4px 14px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: #fff;
  font-size: 13px;
  color: var(--text-light);
  /* 自定义分组 chip 超长时截断，不撑破筛选栏 */
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chip.active {
  background: #eef1ff;
  border-color: var(--primary);
  color: var(--primary);
  font-weight: 600;
}

.search-input {
  min-width: 220px;
  max-width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
}

.batch-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 14px;
  padding: 10px 12px;
  margin-bottom: 12px;
  background: #fafbff;
  border: 1px solid var(--border);
  border-radius: 10px;
  font-size: 14px;
}

.select-all {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}

.empty-tip {
  padding: 28px 0;
  text-align: center;
}

.item-list {
  border-top: 1px solid var(--border);
  /* 防止长内容撑破列表容器导致页面横向滚动 */
  min-width: 0;
  overflow-wrap: anywhere;
}

.item {
  border-bottom: 1px solid var(--border);
}

.item-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 12px 4px;
  cursor: pointer;
  min-width: 0;
}

.item-head:hover {
  background: #fafbff;
}

.item-check {
  /* 复选框固定在行首顶部，避免长题面换行后垂直居中显得突兀 */
  flex-shrink: 0;
  align-self: flex-start;
  margin-top: 4px;
}

.item-question {
  flex: 1 1 240px;
  min-width: 0;
  font-size: 14px;
  /* 题面可能是超长无空格字符串，强制断行避免撑破行容器 */
  overflow-wrap: anywhere;
}

.badges {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-width: 100%;
  min-width: 0;
}

.badges .badge {
  /* 自定义分组名最长 64 字，超长截断避免单枚标撑爆整行 */
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.badge.outline {
  background: transparent;
  border: 1px solid var(--border);
  color: var(--text-light);
}

.expand-icon {
  flex-shrink: 0;
  font-size: 10px;
  color: var(--text-light);
}

.item-body {
  padding: 0 4px 14px 28px;
  min-width: 0;
}

.answer-block {
  margin: 0 0 8px;
  padding: 12px 14px;
  background: #fafbff;
  border: 1px solid var(--border);
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.item-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* 迁移面板：标签选择 + 新建输入横向排列，窄屏自动换行 */
.move-panel {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  padding: 10px 12px;
  background: #fff;
  border: 1px dashed var(--primary);
  border-radius: 10px;
}

.move-label {
  font-size: 13px;
  color: var(--text-light);
}

.move-select {
  padding: 7px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  max-width: 180px;
}

.move-input {
  flex: 1;
  min-width: 160px;
  padding: 7px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
}

.danger-text {
  color: var(--danger, #d4380d);
}

@media (max-width: 767px) {
  .filter-bar {
    flex-direction: column;
    align-items: stretch;
  }

  .search-input {
    width: 100%;
  }

  .batch-bar {
    flex-wrap: wrap;
  }

  .item-body {
    padding-left: 4px;
  }

  .move-select {
    max-width: 100%;
  }
}
</style>
