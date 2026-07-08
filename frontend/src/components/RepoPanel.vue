<template>
  <section>
    <div class="page-head">
      <div>
        <h2 class="page-head__title">仓库管理</h2>
        <p class="page-head__desc">注册 Git 仓库后可克隆、查看 commit、发起审查</p>
      </div>
      <div class="page-head__actions">
        <button class="btn btn--ghost btn--sm" @click="loadRepos">
          <BaseIcon name="refresh-cw" :size="13" />
          <span>刷新</span>
        </button>
      </div>
    </div>

    <!-- Card 1：注册新仓库 -->
    <div class="card">
      <header class="card__head">
        <div class="card__title">
          <span class="tag tag--blue">NEW</span>
          <span>注册新仓库</span>
        </div>
      </header>
      <div class="card__body">
        <form @submit.prevent="submitForm">
          <div class="form-row">
            <div class="field" style="flex:1;">
              <label class="field__label">仓库名称 <span style="color:var(--danger)">*</span></label>
              <input class="input" v-model="form.name" placeholder="my-project" required>
            </div>
            <div class="field" style="flex:0 0 140px;">
              <label class="field__label">类型</label>
              <select class="select" v-model="form.type">
                <option value="REMOTE">REMOTE</option>
                <option value="LOCAL">LOCAL</option>
              </select>
            </div>
          </div>
          <div class="field" style="margin-top:16px;">
            <label class="field__label">仓库地址 <span style="color:var(--danger)">*</span></label>
            <input class="input" v-model="form.url" placeholder="https://github.com/your/repo.git" required>
          </div>
          <div class="field" style="margin-top:16px;">
            <label class="field__label">默认分支</label>
            <input class="input" v-model="form.defaultBranch" placeholder="main">
          </div>
          <div class="form-actions">
            <button class="btn btn--primary" type="submit">
              <BaseIcon name="plus" :size="14" />
              <span>注册</span>
            </button>
          </div>
        </form>
      </div>
    </div>

    <!-- Card 2：已注册仓库列表 -->
    <div class="card">
      <header class="card__head">
        <div class="card__title">
          <span class="tag tag--gray">LIST</span>
          <span>已注册仓库</span>
          <span class="pill">{{ state.repos.length }}</span>
        </div>
        <span class="card__hint">克隆 / 审查 / 删除</span>
      </header>
      <div class="card__body" style="padding:0;">
        <div v-if="state.repos.length === 0" class="empty" style="padding:48px;">
          <span class="empty__icon"><BaseIcon name="database" :size="20" /></span>
          <span class="empty__title">暂无仓库</span>
          <span class="empty__desc">在上方表单注册你的第一个仓库</span>
        </div>
        <table v-else class="table">
          <thead>
            <tr>
              <th style="width:60px;">ID</th>
              <th>仓库</th>
              <th style="width:90px;">类型</th>
              <th style="width:120px;">状态</th>
              <th style="width:200px; text-align:right;">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in state.repos" :key="r.id">
              <td style="font-family:var(--font-mono); color:var(--fg-muted);">#{{ r.id }}</td>
              <td>
                <div style="font-weight:500; color:var(--fg);">{{ r.name }}</div>
                <div style="font-family:var(--font-mono); font-size:12px; color:var(--fg-muted); margin-top:2px;">{{ r.url }}</div>
                <div v-if="r.defaultBranch" style="font-size:11px; color:var(--fg-subtle); margin-top:2px;">默认分支: {{ r.defaultBranch }}</div>
              </td>
              <td><span class="pill">{{ r.type || 'REMOTE' }}</span></td>
              <td>
                <span class="repo-status" :data-status="r.status || 'UNCLONED'">
                  <span class="repo-status__dot"></span>
                  {{ r.status || 'UNCLONED' }}
                </span>
              </td>
              <td style="text-align:right;">
                <button class="btn btn--ghost btn--sm" @click="cloneRepo(r.id)">
                  <BaseIcon name="copy" :size="12" />
                  <span>克隆</span>
                </button>
                <button class="btn btn--ghost btn--sm" @click="goReview(r.id)">
                  <BaseIcon name="file-code" :size="12" />
                  <span>审查</span>
                </button>
                <button class="btn btn--ghost btn--sm" style="color:var(--danger);" @click="deleteRepo(r.id)">
                  <BaseIcon name="trash" :size="12" />
                  <span>删除</span>
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>

<script setup>
import { reactive } from 'vue'
import { state, setConn, showToast, api } from '../composables/useApi.js'
import BaseIcon from './BaseIcon.vue'

const form = reactive({ name: '', url: '', type: 'REMOTE', defaultBranch: 'main' })

async function loadRepos() {
  try {
    setConn('running', 'LOADING')
    state.repos = await api('/repo')
    setConn('idle', 'API IDLE')
    showToast('已刷新仓库列表', 'success')
  } catch (e) {
    showToast('加载失败: ' + e.message, 'error')
    setConn('error', 'ERROR')
  }
}

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
