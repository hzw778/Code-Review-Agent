<template>
  <aside class="app-sidebar">
    <!-- 导航区 -->
    <nav class="sidebar__nav">
      <div class="sidebar__section">
        <div class="sidebar__label">导航</div>
        <button
          v-for="t in navItems"
          :key="t.key"
          class="nav-item"
          :class="{ 'is-active': activeTab === t.key }"
          @click="$emit('switch-tab', t.key)"
        >
          <span class="nav-item__icon">
            <BaseIcon :name="t.icon" :size="16" />
          </span>
          <span>{{ t.label }}</span>
          <span v-if="t.count != null" class="nav-item__count">{{ t.count }}</span>
        </button>
      </div>

      <!-- 底部说明区 -->
      <div class="sidebar__section">
        <div class="sidebar__label">说明</div>
        <div class="sidebar__tip">
          <BaseIcon name="info" :size="13" />
          <span>仓库列表已移至「代码审查」页面，仓库管理请前往「仓库管理」</span>
        </div>
      </div>
    </nav>

    <!-- 底部版本信息 -->
    <footer class="sidebar__foot">
      <span class="sidebar__ver">ReAct Console v2.0</span>
    </footer>
  </aside>
</template>

<script setup>
import BaseIcon from '../BaseIcon.vue'

defineProps({
  activeTab: { type: String, default: 'review' },
})

defineEmits(['switch-tab'])

const navItems = [
  { key: 'review', label: '代码审查', icon: 'file-code' },
  { key: 'chat', label: '智能问答', icon: 'message-square' },
  { key: 'repo', label: '仓库管理', icon: 'database' },
]
</script>

<style scoped>
.sidebar__tip{
  display: flex; align-items: flex-start; gap: 6px;
  padding: var(--sp-2) var(--sp-3);
  font-size: 12px; line-height: 1.5;
  color: var(--fg-muted);
  background: var(--bg-subtle);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
}
.sidebar__foot{
  padding: var(--sp-3) var(--sp-4);
  border-top: 1px solid var(--border);
  flex-shrink: 0;
}
.sidebar__ver{
  font-size: 11px; color: var(--fg-subtle);
  font-family: var(--font-mono);
}
</style>
