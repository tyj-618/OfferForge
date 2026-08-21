<script setup>
import { computed, onMounted, ref } from 'vue'
import { adminApi } from '../api'
import { toast } from '../toast'

// 管理台：统计概览 + 用户分页检索 + 封禁/解封（二次确认模态）
const stats = ref({ totalUsers: 0, todayNew: 0, bannedUsers: 0 })
const keyword = ref('')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const items = ref([])
const loading = ref(false)
const loadError = ref('')

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

async function loadStats() {
  try {
    stats.value = await adminApi.stats()
  } catch (error) {
    // 非管理员访问返回 40300：页面保持空态即可，无需打扰用户
    loadError.value = error.message || '加载失败'
  }
}

async function loadUsers() {
  loading.value = true
  try {
    const result = await adminApi.users({
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size: size.value
    })
    items.value = result.items
    total.value = result.total
  } catch (error) {
    loadError.value = error.message || '加载失败'
    toast.error(loadError.value)
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  loadUsers()
}

function gotoPage(target) {
  if (target < 1 || target > totalPages.value || target === page.value) {
    return
  }
  page.value = target
  loadUsers()
}

// 封禁二次确认：仅记录目标用户，确认后调接口并刷新列表与统计
const banTarget = ref(null)
const banBusy = ref(false)

function requestBan(user) {
  banTarget.value = user
}

function cancelBan() {
  banTarget.value = null
}

async function confirmBan() {
  if (!banTarget.value || banBusy.value) {
    return
  }
  banBusy.value = true
  try {
    await adminApi.ban(banTarget.value.id)
    toast.success(`已封禁用户 ${banTarget.value.username}`)
    banTarget.value = null
    await Promise.all([loadStats(), loadUsers()])
  } catch (error) {
    toast.error(error.message || '封禁失败')
  } finally {
    banBusy.value = false
  }
}

async function unban(user) {
  try {
    await adminApi.unban(user.id)
    toast.success(`已解封用户 ${user.username}`)
    await Promise.all([loadStats(), loadUsers()])
  } catch (error) {
    toast.error(error.message || '解封失败')
  }
}

onMounted(() => {
  Promise.all([loadStats(), loadUsers()])
})
</script>

<template>
  <div class="admin-page">
    <section class="admin-card">
      <h2 class="page-title">管理台</h2>
      <p v-if="loadError" class="forbidden-tip">{{ loadError }}（仅管理员可访问此页面）</p>
      <template v-else>
        <div class="stats-row">
          <div class="stat-box">
            <span class="stat-value">{{ stats.totalUsers }}</span>
            <span class="stat-label">用户总数</span>
          </div>
          <div class="stat-box">
            <span class="stat-value">{{ stats.todayNew }}</span>
            <span class="stat-label">今日新增</span>
          </div>
          <div class="stat-box">
            <span class="stat-value">{{ stats.bannedUsers }}</span>
            <span class="stat-label">封禁用户</span>
          </div>
        </div>

        <div class="toolbar">
          <input
            v-model="keyword"
            class="search-input"
            placeholder="搜索用户名 / 昵称 / 邮箱"
            @keyup.enter="search"
          />
          <button class="primary" @click="search">搜索</button>
        </div>

        <table class="user-table">
          <thead>
            <tr>
              <th>ID</th>
              <th>用户名</th>
              <th>昵称</th>
              <th>邮箱</th>
              <th>状态</th>
              <th>注册时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading">
              <td colspan="7" class="empty-cell">加载中…</td>
            </tr>
            <tr v-else-if="items.length === 0">
              <td colspan="7" class="empty-cell">暂无数据</td>
            </tr>
            <tr v-for="user in items" v-else :key="user.id">
              <td>{{ user.id }}</td>
              <td>
                {{ user.username }}
                <span v-if="user.admin" class="admin-badge">管理员</span>
              </td>
              <td>{{ user.nickname || '-' }}</td>
              <td>{{ user.email || '-' }}</td>
              <td>
                <span :class="['status-badge', user.status === 0 ? 'status-ok' : 'status-banned']">
                  {{ user.status === 0 ? '正常' : '已封禁' }}
                </span>
              </td>
              <td>{{ user.createdAt || '-' }}</td>
              <td>
                <template v-if="user.status === 0">
                  <button class="danger" :disabled="user.admin" @click="requestBan(user)">
                    封禁
                  </button>
                </template>
                <template v-else>
                  <button class="ghost" @click="unban(user)">解封</button>
                </template>
              </td>
            </tr>
          </tbody>
        </table>

        <div class="pagination">
          <button class="ghost" :disabled="page <= 1" @click="gotoPage(page - 1)">上一页</button>
          <span class="page-info">{{ page }} / {{ totalPages }} 页，共 {{ total }} 人</span>
          <button class="ghost" :disabled="page >= totalPages" @click="gotoPage(page + 1)">
            下一页
          </button>
        </div>
      </template>
    </section>

    <!-- 封禁二次确认模态：风格与快捷提问清空会话确认一致 -->
    <div v-if="banTarget" class="modal-overlay" @click.self="cancelBan">
      <div class="modal-card">
        <h3>确认封禁</h3>
        <p>
          封禁后 <b>{{ banTarget.username }}</b> 将无法再登录，确定要封禁该用户吗？
        </p>
        <div class="modal-actions">
          <button class="ghost" @click="cancelBan">取消</button>
          <button class="danger" :disabled="banBusy" @click="confirmBan">
            {{ banBusy ? '处理中…' : '确认封禁' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.admin-page {
  max-width: 1080px;
  margin: 24px auto;
  padding: 0 20px;
}

.admin-card {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 24px;
}

.page-title {
  margin: 0 0 16px;
  font-size: 20px;
}

.forbidden-tip {
  color: var(--text-light);
}

.stats-row {
  display: flex;
  gap: 16px;
  margin-bottom: 20px;
}

.stat-box {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 16px;
  background: #f5f6fa;
  border-radius: var(--radius);
}

.stat-value {
  font-size: 26px;
  font-weight: 700;
  color: var(--primary);
}

.stat-label {
  font-size: 13px;
  color: var(--text-light);
}

.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
}

.search-input {
  flex: 1;
  max-width: 360px;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  font-size: 14px;
}

.user-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.user-table th,
.user-table td {
  padding: 10px 12px;
  text-align: left;
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}

.user-table th {
  color: var(--text-light);
  font-weight: 600;
  background: #f5f6fa;
}

.empty-cell {
  text-align: center !important;
  color: var(--text-light);
  padding: 24px !important;
}

.admin-badge {
  display: inline-block;
  margin-left: 6px;
  padding: 1px 8px;
  font-size: 12px;
  color: var(--primary);
  background: rgba(59, 130, 246, 0.1);
  border-radius: 999px;
}

.status-badge {
  display: inline-block;
  padding: 2px 10px;
  font-size: 12px;
  border-radius: 999px;
}

.status-ok {
  color: #16a34a;
  background: rgba(22, 163, 74, 0.1);
}

.status-banned {
  color: #dc2626;
  background: rgba(220, 38, 38, 0.1);
}

.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 16px;
}

.page-info {
  font-size: 13px;
  color: var(--text-light);
}

button.danger {
  padding: 6px 14px;
  border: none;
  border-radius: var(--radius);
  background: #dc2626;
  color: #fff;
  font-size: 13px;
  cursor: pointer;
}

button.danger:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.modal-overlay {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.4);
  z-index: 50;
}

.modal-card {
  width: 360px;
  max-width: calc(100vw - 40px);
  background: var(--card);
  border-radius: var(--radius);
  padding: 22px;
}

.modal-card h3 {
  margin: 0 0 10px;
  font-size: 16px;
}

.modal-card p {
  margin: 0 0 18px;
  font-size: 14px;
  color: var(--text-light);
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

@media (max-width: 767px) {
  .admin-card {
    padding: 16px;
  }

  .stats-row {
    flex-direction: column;
  }

  .user-table {
    display: block;
    overflow-x: auto;
  }
}
</style>
