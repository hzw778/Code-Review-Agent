<template>
  <section class="tab-panel">
    <div class="card">
      <header class="card__head">
        <span class="tag">REPO</span>
        <h2 class="card__title">注册新仓库</h2>
        <span class="card__hint">注册后可克隆、查看 commit、发起审查</span>
      </header>
      <form class="form" @submit.prevent="submitForm">
        <label class="field">
          <span class="field__label">name（仓库名，唯一）</span>
          <input class="field__input" v-model="form.name" required>
        </label>
        <label class="field">
          <span class="field__label">url（仓库地址）</span>
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

    <div class="card">
      <header class="card__head">
        <span class="tag">LIST</span>
        <h2 class="card__title">已注册仓库</h2>
        <span class="card__hint">点击操作按钮可克隆 / 删除 / 发起审查</span>
      </header>
      <div class="repo-table">
        <div v-if="state.repos.length === 0" class="repo-empty">暂无仓库</div>
        <div v-for="r in state.repos" :key="r.id" class="repo-row">
          <div class="repo-row__id">#{{ r.id }}</div>
          <div class="repo-row__name">
            <div>{{ r.name }}</div>
            <div>{{ r.url }}</div>
          </div>
          <div class="repo-row__type">{{ r.type || 'REMOTE' }}</div>
          <div class="repo-row__status" :data-status="r.status || 'UNCLONED'">{{ r.status || 'UNCLONED' }}</div>
          <div class="repo-row__actions">
            <button class="btn btn--ghost btn--sm" @click="cloneRepo(r.id)">克隆</button>
            <button class="btn btn--ghost btn--sm" @click="goReview(r.id)">审查</button>
            <button class="btn btn--ghost btn--sm" @click="deleteRepo(r.id)">删除</button>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { reactive } from 'vue'
import { state, setConn, showToast, api } from '../composables/useApi.js'

const form = reactive({ name: '', url: '', type: 'REMOTE', defaultBranch: 'main' })

async function submitForm() {
  try {
    setConn('running', 'SAVING')
    await api('/repo', { method: 'POST', body: JSON.stringify({ ...form }) })
    showToast('注册成功', 'success')
    form.name = ''; form.url = ''; form.type = 'REMOTE'; form.defaultBranch = 'main'
    state.repos = await api('/repo')
    setConn('idle', 'API IDLE')
  } catch (e) {
    showToast('注册失败: ' + e.message, 'error')
    setConn('error', 'ERROR')
  }
}

async function cloneRepo(id) {
  try {
    setConn('running', 'CLONING')
    showToast('开始克隆, 大仓库可能耗时较长...', 'info')
    await api(`/repo/${id}/clone`, { method: 'POST' })
    showToast('克隆成功', 'success')
    state.repos = await api('/repo')
    setConn('idle', 'API IDLE')
  } catch (e) {
    showToast('克隆失败: ' + e.message, 'error')
    setConn('error', 'ERROR')
  }
}

function goReview(id) {
  state.selectedRepoId = id
  showToast('已选择仓库，请到审查页操作', 'success')
}

async function deleteRepo(id) {
  if (!confirm('确认删除仓库?')) return
  try {
    await api(`/repo/${id}`, { method: 'DELETE' })
    showToast('已删除', 'success')
    if (state.selectedRepoId === id) state.selectedRepoId = null
    state.repos = await api('/repo')
  } catch (e) {
    showToast('删除失败: ' + e.message, 'error')
  }
}
</script>
