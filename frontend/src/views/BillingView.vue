<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { billingApi, billingState, refreshBillingState } from '../api'
import { classifyError } from '../utils/errors'
import { toast } from '../toast'

// 充值中心：余额卡片 + 充值档位（下单 → 模拟支付 → 轮询到账）+ 模型价目 + 消费流水。
// 审核期（计费开关关闭）页面完整展示，仅充值下单按钮提示审核中；
// 支付渠道审核中且开关开启时走 mock 渠道模拟支付，页面顶部给出内测提示条。

const packages = ref([])
const models = ref([])
const transactions = ref([])
const orders = ref([])
const loading = ref(true)
const localError = ref('')

const selectedPackageId = ref('')
const paying = ref(false)
// 待支付订单（下单成功 → 弹支付面板 → 模拟支付 → 轮询状态直至到账）
const pendingOrder = ref(null)
let pollTimer = null

const balanceYuan = computed(() => (billingState.balanceCents / 100).toFixed(2))

// 审核期：开关关闭时页面正常展示，充值动作统一提示审核中（后端下单/支付同样拒绝兜底）
const underReview = computed(() => billingState.loaded && !billingState.enabled)

function yuan(cents) {
  return (cents / 100).toFixed(2)
}

function formatTime(text) {
  return (text || '').replace('T', ' ').slice(0, 16)
}

const typeLabels = { RECHARGE: '充值', CONSUME: '消费', REFUND: '退款' }

onMounted(async () => {
  await loadAll()
})

onUnmounted(() => {
  stopPolling()
})

async function loadAll() {
  loading.value = true
  localError.value = ''
  try {
    const [, packageList, modelList, transactionList, orderList] = await Promise.all([
      refreshBillingState(),
      billingApi.packages(),
      billingApi.models(),
      billingApi.transactions(),
      billingApi.orders()
    ])
    packages.value = packageList || []
    models.value = modelList || []
    transactions.value = transactionList || []
    orders.value = orderList || []
  } catch (e) {
    localError.value = classifyError(e).message
  } finally {
    loading.value = false
  }
}

function selectPackage(packageId) {
  selectedPackageId.value = selectedPackageId.value === packageId ? '' : packageId
}

async function createOrder() {
  if (paying.value) {
    return
  }
  // 审核期：不发起下单，统一提示（后端 requireEnabled 同样会拒绝）
  if (underReview.value) {
    toast.info('相关功能正在审核中，敬请期待')
    return
  }
  if (!selectedPackageId.value) {
    return
  }
  paying.value = true
  try {
    const order = await billingApi.createOrder(selectedPackageId.value)
    pendingOrder.value = order
  } catch (e) {
    toast.error(classifyError(e).message)
  } finally {
    paying.value = false
  }
}

// 模拟支付：确认后轮询订单状态直至 PAID（到账即刷新余额与流水）
async function confirmMockPay() {
  if (!pendingOrder.value || paying.value) {
    return
  }
  paying.value = true
  try {
    await billingApi.mockPay(pendingOrder.value.orderNo)
    await pollOrderUntilSettled(pendingOrder.value.orderNo)
  } catch (e) {
    toast.error(classifyError(e).message)
    paying.value = false
  }
}

async function pollOrderUntilSettled(orderNo) {
  stopPolling()
  let attempts = 0
  const poll = async () => {
    attempts += 1
    try {
      const order = await billingApi.order(orderNo)
      if (order?.status === 'PAID') {
        stopPolling()
        pendingOrder.value = null
        paying.value = false
        toast.success('充值到账成功')
        await loadAll()
        return
      }
      if (order?.status === 'CANCELLED' || attempts >= 24) {
        stopPolling()
        paying.value = false
        toast.error(order?.status === 'CANCELLED' ? '订单已取消' : '支付确认超时，请稍后刷新查看')
      }
    } catch {
      if (attempts >= 24) {
        stopPolling()
        paying.value = false
      }
    }
  }
  await poll()
  if (pollTimer === null && pendingOrder.value) {
    pollTimer = setInterval(poll, 2500)
  }
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function cancelPay() {
  stopPolling()
  pendingOrder.value = null
  paying.value = false
}
</script>

<template>
  <div class="page">
    <h1 class="page-title">充值中心</h1>

    <p v-if="underReview" class="beta-hint">
      充值功能正在审核中，当前页面仅供查看余额与价目；点击充值时暂不可用，审核通过后自动开放。
    </p>
    <p v-else-if="billingState.provider === 'mock'" class="beta-hint">
      内测提示：支付渠道审核中，当前为模拟支付（点击确认即到账），不涉及真实资金。
    </p>

    <div v-if="localError" class="card">
      <p class="muted">{{ localError }}</p>
    </div>

    <template v-else-if="!loading">
      <!-- 余额卡片 -->
      <div class="card balance-card">
        <div class="balance-main">
          <span class="muted">当前余额（元）</span>
          <span class="balance-amount">{{ balanceYuan }}</span>
        </div>
        <p class="muted balance-desc">
          免费额度用完后，面试/训练将自动转为余额计费模式：按所选模型 token 用量从余额扣费。
        </p>
      </div>

      <!-- 充值档位 -->
      <div class="card">
        <h2>充值档位</h2>
        <div class="package-grid">
          <button
            v-for="pack in packages"
            :key="pack.id"
            type="button"
            class="package-card"
            :class="{ selected: selectedPackageId === pack.id }"
            @click="selectPackage(pack.id)"
          >
            <span class="package-amount">¥{{ yuan(pack.amountCents) }}</span>
            <span class="muted">{{ pack.name }}</span>
          </button>
        </div>
        <div class="package-actions">
          <button :disabled="(!underReview && !selectedPackageId) || paying" @click="createOrder">
            {{ paying ? '处理中…' : '立即充值' }}
          </button>
        </div>
      </div>

      <!-- 模型价目 -->
      <div class="card">
        <h2>模型价目</h2>
        <p class="muted">计费模式下按模型价目 × 120% 折算扣费（分/百万 token）；付费模型需余额支撑。</p>
        <table class="price-table">
          <thead>
            <tr>
              <th>模型</th>
              <th>输入价（元/百万 token）</th>
              <th>输出价（元/百万 token）</th>
              <th>可用范围</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="model in models" :key="model.id">
              <td>{{ model.name }}</td>
              <td>{{ yuan(model.inputPerMillionCents) }}</td>
              <td>{{ yuan(model.outputPerMillionCents) }}</td>
              <td>
                <span v-if="model.paidOnly" class="tag-paid">付费模型 🔒</span>
                <span v-else class="tag-free">免费可用</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 消费流水 -->
      <div class="card">
        <h2>最近流水</h2>
        <p v-if="!transactions.length" class="muted">暂无记录</p>
        <table v-else class="price-table">
          <thead>
            <tr>
              <th>时间</th>
              <th>类型</th>
              <th>金额（元）</th>
              <th>余额（元）</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(item, index) in transactions" :key="index">
              <td>{{ formatTime(item.createdAt) }}</td>
              <td>{{ typeLabels[item.type] || item.type }}</td>
              <td :class="item.type === 'CONSUME' ? 'amount-out' : 'amount-in'">
                {{ item.type === 'CONSUME' ? '-' : '+' }}{{ yuan(item.amountCents) }}
              </td>
              <td>{{ yuan(item.balanceAfterCents) }}</td>
              <td class="muted">{{ item.detail || item.refNo || '-' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 充值订单 -->
      <div class="card">
        <h2>充值订单</h2>
        <p v-if="!orders.length" class="muted">暂无订单</p>
        <table v-else class="price-table">
          <thead>
            <tr>
              <th>订单号</th>
              <th>金额（元）</th>
              <th>状态</th>
              <th>创建时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="order in orders" :key="order.orderNo">
              <td>{{ order.orderNo }}</td>
              <td>{{ yuan(order.amountCents) }}</td>
              <td>
                <span v-if="order.status === 'PAID'" class="tag-free">已支付</span>
                <span v-else-if="order.status === 'PENDING'" class="tag-paid">待支付</span>
                <span v-else class="muted">已取消</span>
              </td>
              <td>{{ formatTime(order.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- 模拟支付面板：应用内模态窗（与全站确认交互一致） -->
    <div v-if="pendingOrder" class="modal-overlay" @click.self="cancelPay">
      <div class="modal-card">
        <h3>模拟支付</h3>
        <p>订单号：{{ pendingOrder.orderNo }}</p>
        <p>
          支付金额：<strong>¥{{ yuan(pendingOrder.amountCents) }}</strong>
        </p>
        <p v-if="pendingOrder.payHint" class="muted">{{ pendingOrder.payHint }}</p>
        <div class="modal-actions">
          <button class="secondary" :disabled="paying" @click="cancelPay">取消</button>
          <button :disabled="paying" @click="confirmMockPay">{{ paying ? '支付确认中…' : '确认支付（模拟）' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  max-width: 920px;
  margin: 0 auto;
  padding: 24px 16px 48px;
}

.page-title {
  margin-bottom: 16px;
}

.beta-hint {
  margin-bottom: 14px;
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 13px;
  background: #fff7e6;
  border: 1px solid #ffe3ad;
}

.card {
  margin-bottom: 16px;
}

.card h2 {
  margin-bottom: 10px;
  font-size: 16px;
}

.balance-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.balance-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.balance-amount {
  font-size: 34px;
  font-weight: 700;
  color: var(--primary);
}

.balance-desc {
  font-size: 13px;
}

.package-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 12px;
  margin-top: 12px;
}

.package-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: #f5f6fa;
  color: var(--text);
  cursor: pointer;
  text-align: center;
}

.package-card:hover {
  border-color: var(--primary);
  background: #fff;
}

.package-card.selected {
  border-color: var(--primary);
  background: #fff;
  box-shadow: 0 0 0 2px rgba(79, 70, 229, 0.15);
}

.package-amount {
  font-size: 20px;
  font-weight: 700;
}

.package-actions {
  margin-top: 14px;
}

.price-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  margin-top: 10px;
}

.price-table th,
.price-table td {
  padding: 8px 10px;
  border-bottom: 1px solid var(--border);
  text-align: left;
}

.price-table th {
  color: var(--text-light);
  font-weight: 500;
}

.tag-paid {
  color: #b45309;
}

.tag-free {
  color: #047857;
}

.amount-in {
  color: #047857;
}

.amount-out {
  color: #b91c1c;
}

.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.modal-card {
  background: var(--card, #fff);
  border: 1px solid var(--border);
  border-radius: var(--radius, 12px);
  padding: 22px;
  width: min(420px, 90vw);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.18);
}

.modal-card h3 {
  margin-bottom: 10px;
}

.modal-card p {
  margin: 6px 0;
  font-size: 14px;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 16px;
}

@media (max-width: 767px) {
  .price-table {
    display: block;
    overflow-x: auto;
  }
}
</style>
