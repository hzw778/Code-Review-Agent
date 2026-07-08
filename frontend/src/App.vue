<template>
  <div class="app-shell">
    <!-- 顶部栏 -->
    <AppHeader :active-tab="activeTab" />

    <!-- 左侧导航 -->
    <AppSidebar
      ref="sidebarRef"
      :active-tab="activeTab"
      @switch-tab="onSwitchTab"
    />

    <!-- 主内容区 -->
    <main class="app-main">
      <div class="app-main__inner">
        <ReviewPanel v-show="activeTab === 'review'" @traj-update="onReviewTraj" />
        <ChatPanel v-show="activeTab === 'chat'" @chat-traj="onChatTraj" />
        <RepoPanel v-show="activeTab === 'repo'" />
      </div>
    </main>

    <!-- 右侧轨迹抽屉 -->
    <TrajectoryDrawer ref="trajPanelRef" />

    <!-- Toast -->
    <div class="toast" :data-type="state.toastType" :class="{ show: state.toastVisible }">
      <BaseIcon
        :name="toastIcon"
        :size="15"
        color="#fff"
        v-if="state.toastType !== 'info'"
      />
      <span>{{ state.toastMsg }}</span>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { state, setConn, showToast, api } from './composables/useApi.js'
import BaseIcon from './components/BaseIcon.vue'
import AppHeader from './components/layout/AppHeader.vue'
import AppSidebar from './components/layout/AppSidebar.vue'
import TrajectoryDrawer from './components/layout/TrajectoryDrawer.vue'
import ReviewPanel from './components/ReviewPanel.vue'
import ChatPanel from './components/ChatPanel.vue'
import RepoPanel from './components/RepoPanel.vue'

const activeTab = ref('review')
const sidebarRef = ref(null)
const trajPanelRef = ref(null)

const toastIcon = computed(() => {
  if (state.toastType === 'success') return 'check-circle'
  if (state.toastType === 'error') return 'alert-circle'
  if (state.toastType === 'warning') return 'alert-triangle'
  return 'info'
})

// 切换主面板
function onSwitchTab(key) {
  activeTab.value = key
  // 切换到非审查页时，轨迹模式跟随
  if (key === 'chat') {
    state.trajMode = 'chat'
  } else if (key === 'review') {
    state.trajMode = 'review'
  }
}

// ReviewPanel 轨迹更新 → 转发给 TrajectoryDrawer
function onReviewTraj(data) {
  trajPanelRef.value?.renderReviewTraj(data)
}

// ChatPanel SSE META 事件 → 转发给 TrajectoryDrawer
function onChatTraj(ev) {
  trajPanelRef.value?.renderChatTraj(ev)
}

// 加载仓库列表
async function loadRepos() {
  try {
    setConn('running', 'LOADING')
    state.repos = await api('/repo')
    setConn('idle', 'API IDLE')
  } catch (e) {
    showToast('加载仓库失败: ' + e.message, 'error')
    setConn('error', 'ERROR')
  }
}

// 键盘快捷键：T 切换轨迹抽屉
function onKeydown(e) {
  if ((e.key === 't' || e.key === 'T')) {
    if (document.activeElement && ['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement.tagName)) return
    state.sideOpen = !state.sideOpen
  }
}

onMounted(() => {
  setConn('idle', 'API IDLE')
  loadRepos()
  document.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  document.removeEventListener('keydown', onKeydown)
})
</script>
