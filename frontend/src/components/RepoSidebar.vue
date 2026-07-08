<template>
  <aside class="repo-pane">
    <header class="repo-pane__head">
      <h3 class="repo-pane__title">仓库列表</h3>
      <button class="btn btn--ghost btn--sm" title="刷新" @click="loadRepos">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12a9 9 0 1 1-3-6.7"/><path d="M21 3v6h-6"/></svg>
      </button>
    </header>
    <button class="btn btn--primary btn--block" @click="showModal = true">+ 注册仓库</button>
    <div class="repo-list">
      <div v-if="state.repos.length === 0" class="repo-empty">暂无仓库</div>
      <div v-for="r in state.repos" :key="r.id"
        class="repo-item" :class="{ 'is-selected': state.selectedRepoId === r.id }"
        @click="selectRepo(r.id)">
        <div class="repo-item__name">{{ r.name }}</div>
        <div class="repo-item__url">{{ r.url }}</div>
        <div class="repo-item__meta">
          <span class="pill">{{ r.type || 'REMOTE' }}</span>
          <span class="pill" :data-status="r.status || 'UNCLONED'">{{ r.status || 'UNCLONED' }}</span>
        </div>
      </div>
    </div>

    <!-- 快速注册仓库 Modal -->
    <div v-if="showModal" class="modal-mask" @click.self="showModal = false">
      <div class="modal">
        <header class="modal__head">
          <h3>注册仓库</h3>
          <button class="btn btn--icon btn--ghost" @click="showModal = false">×</button>
        </header>
        <div class="modal__body">
          <form class="form" @submit.prevent="submitRepo">
            <label class="field">
              <span class="field__label">name</span>
              <input class="field__input" v-model="form.name" required>
            </label>
            <label class="field">
              <span class="field__label">url</span>
              <input class="field__input" v-model="form.url" placeholder="https://github.com/your/repo.git" required>
            </label>
            <div class="select-row">
              <label class="field" style="flex:0 0 140px;">
                <span class="field__label">type</span>
                <select class="field__input" v-model="form.type">
                  <option value="REMOTE">REMOTE</option>
                  <option value="LOCAL">LOCAL</option>
                </select>
              </label>
              <label class="field" style="flex:1;">
                <span class="field__label">defaultBranch</span>
                <input class="field__input" v-model="form.defaultBranch">
              </label>
            </div>
            <div class="form__actions">
              <button class="btn btn--primary" type="submit"><span>注册</span><span class="btn__arrow">→</span></button>
            </div>
          </form>
        </div>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { state, setConn, showToast, api } from '../composables/useApi.js'

const showModal = ref(false)
const form = reactive({ name: '', url: '', type: 'REMOTE', defaultBranch: 'main' })

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

function selectRepo(id) {
  state.selectedRepoId = id
  const repo = state.repos.find(r => r.id === id)
  showToast('已选择仓库: ' + (repo?.name || ''), 'success')
}

async function submitRepo() {
  try {
    setConn('running', 'SAVING')
    await api('/repo', { method: 'POST', body: JSON.stringify({ ...form }) })
    showToast('注册成功', 'success')
    showModal.value = false
    form.name = ''; form.url = ''; form.type = 'REMOTE'; form.defaultBranch = 'main'
    await loadRepos()
  } catch (e) {
    showToast('注册失败: ' + e.message, 'error')
    setConn('error', 'ERROR')
  }
}

defineExpose({ loadRepos })
</script>
