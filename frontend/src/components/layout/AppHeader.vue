<template>
  <header class="app-header">
    <!-- 左侧：品牌 + 副标题 -->
    <div class="app-header__left">
      <div class="brand">
        <span class="brand__logo">
          <BaseIcon name="git-branch" :size="16" color="#fff" />
        </span>
        <span class="brand__name">Code Review Agent</span>
        <span class="brand__sub">ReAct Console</span>
      </div>
      <span class="header-divider"></span>
      <span class="header-crumb">{{ currentTabLabel }}</span>
    </div>

    <!-- 右侧：连接状态 + 轨迹开关 -->
    <div class="app-header__right">
      <div class="conn-status" :data-state="state.connState">
        <span class="conn-status__dot"></span>
        <span>{{ state.connLabel }}</span>
      </div>
      <button
        class="btn btn--ghost btn--sm"
        :class="{ 'is-active': state.sideOpen }"
        title="数据轨迹（T）"
        @click="toggleDrawer"
      >
        <BaseIcon name="terminal" :size="14" />
        <span>轨迹</span>
      </button>
    </div>
  </header>
</template>

<script setup>
import { computed } from 'vue'
import { state } from '../../composables/useApi.js'
import BaseIcon from '../BaseIcon.vue'

const props = defineProps({
  activeTab: { type: String, default: 'review' },
})

const emit = defineEmits(['toggle-drawer'])

const TAB_LABELS = {
  review: '代码审查',
  chat: '智能问答',
  repo: '仓库管理',
}

const currentTabLabel = computed(() => TAB_LABELS[props.activeTab] || '')

function toggleDrawer() {
  state.sideOpen = !state.sideOpen
  emit('toggle-drawer')
}
</script>

<style scoped>
.header-crumb{
  font-size: 13px; color: var(--fg-muted);
  font-weight: 500;
}
.btn.is-active{
  background: var(--primary-bg);
  color: var(--primary);
  border-color: var(--primary-border);
}
</style>
