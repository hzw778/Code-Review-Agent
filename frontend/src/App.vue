<template>
  <div>
    <div class="bg-grid"></div>
    <div class="bg-glow bg-glow--a"></div>
    <div class="bg-glow bg-glow--b"></div>

    <!-- ============== Topbar ============== -->
    <header class="topbar">
      <div class="topbar__left">
        <div class="logo">
          <span class="logo__mark">◈</span>
          <span class="logo__text">code.review<span class="logo__dim">/agent</span></span>
        </div>
        <span class="topbar__sep"></span>
        <span class="topbar__sub">ReAct Console v2.0 · Vue</span>
      </div>
      <nav class="tabs">
        <button v-for="(t, i) in tabsList" :key="t.key"
          class="tab" :class="{ 'is-active': activeTab === t.key }"
          :data-idx="String(i + 1).padStart(2, '0')"
          @click="activeTab = t.key">{{ t.label }}</button>
      </nav>
      <div class="topbar__right">
        <div class="conn" :data-state="state.connState">
          <span class="conn__dot"></span><span class="conn__label">{{ state.connLabel }}</span>
        </div>
        <button class="btn btn--ghost btn--icon" title="轨迹面板 (T)" @click="state.sideOpen = !state.sideOpen">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12h4M3 6h18M3 18h18"/></svg>
          <span>轨迹</span>
        </button>
      </div>
    </header>

    <main class="layout">
      <!-- 左侧仓库列表 -->
      <RepoSidebar />

      <!-- 中间主区 -->
      <section class="main-pane">
        <ReviewPanel v-show="activeTab === 'review'" @traj-update="onReviewTraj" />
        <ChatPanel v-show="activeTab === 'chat'" @chat-traj="onChatTraj" />
        <RepoPanel v-show="activeTab === 'repo'" />
      </section>

      <!-- 右侧轨迹面板 -->
      <TrajectoryPanel ref="trajPanelRef" />
    </main>

    <!-- Toast -->
    <div class="toast" :data-type="state.toastType" :class="{ show: state.toastVisible }">
      {{ state.toastMsg }}
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { state, setConn, showToast, api } from './composables/useApi.js'
import RepoSidebar from './components/RepoSidebar.vue'
import ReviewPanel from './components/ReviewPanel.vue'
import ChatPanel from './components/ChatPanel.vue'
import RepoPanel from './components/RepoPanel.vue'
import TrajectoryPanel from './components/TrajectoryPanel.vue'

const tabsList = [
  { key: 'review', label: '代码审查' },
  { key: 'chat', label: '智能问答' },
  { key: 'repo', label: '仓库管理' },
]
const activeTab = ref('review')
const trajPanelRef = ref(null)

// ReviewPanel 轨迹更新 → 转发给 TrajectoryPanel
function onReviewTraj(data) {
  trajPanelRef.value?.renderReviewTraj(data)
}

// ChatPanel SSE META 事件 → 转发给 TrajectoryPanel
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

// 键盘快捷键：T 切换轨迹面板
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

// 暴露给子组件用
defineExpose({ loadRepos, activeTab })
</script>
