<script setup>
import { dismiss, toasts } from '../toast'

const icons = { success: '✅', error: '⚠️', info: 'ℹ️' }

function runAction(item) {
  item.action?.handler?.()
  dismiss(item.id)
}
</script>

<template>
  <div class="toast-hub" aria-live="polite">
    <TransitionGroup name="toast">
      <div v-for="item in toasts" :key="item.id" :class="['toast', item.type]">
        <span class="toast-icon">{{ icons[item.type] || '' }}</span>
        <span class="toast-message">{{ item.message }}</span>
        <button v-if="item.action" class="toast-action" @click="runAction(item)">
          {{ item.action.label }}
        </button>
        <button class="toast-close" aria-label="关闭" @click="dismiss(item.id)">×</button>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toast-hub {
  position: fixed;
  top: 20px;
  right: 20px;
  z-index: 1000;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.toast {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 240px;
  max-width: 380px;
  padding: 12px 14px;
  background: #fff;
  border: 1px solid var(--border);
  border-left: 4px solid var(--text-light);
  border-radius: 10px;
  box-shadow: 0 8px 24px rgba(31, 41, 55, 0.12);
  font-size: 14px;
}

.toast.success {
  border-left-color: var(--success);
}

.toast.error {
  border-left-color: var(--danger);
}

.toast.info {
  border-left-color: var(--primary);
}

.toast-message {
  flex: 1;
}

.toast-action {
  flex-shrink: 0;
  padding: 3px 12px;
  background: transparent;
  color: var(--primary);
  border: 1px solid var(--primary);
  font-weight: 600;
}

.toast-action:hover {
  background: #eef1ff;
}

.toast-close {
  flex-shrink: 0;
  padding: 0 4px;
  background: transparent;
  color: var(--text-light);
  font-size: 16px;
}

.toast-close:hover {
  color: var(--text);
  background: transparent;
}

.toast-enter-active,
.toast-leave-active {
  transition: all 0.3s ease;
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateX(40px);
}
</style>
