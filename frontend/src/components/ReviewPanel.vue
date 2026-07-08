<template>
  <section>
    <!-- ============== Card 1：发起审查 ============== -->
    <div class="card">
      <header class="card__head">
        <div class="card__title">
          <span class="tag tag--blue">REVIEW</span>
          <span>发起审查</span>
        </div>
        <span class="card__hint">选择仓库 + commit，或手动填写</span>
      </header>
      <div class="card__body">
        <!-- 仓库列表（可滚动，仓库多时不会撑爆布局） -->
        <div class="repo-picker">
          <div class="repo-picker__head">
            <label class="field__label">
              选择仓库
              <span class="pill pill--blue" style="margin-left:6px;">{{ state.repos.length }}</span>
            </label>
            <div class="repo-picker__actions">
              <button class="btn btn--ghost btn--sm" title="刷新仓库列表" @click="loadRepos">
                <BaseIcon name="refresh-cw" :size="13" />
              </button>
              <button class="btn btn--ghost btn--sm" title="注册新仓库" @click="showRepoModal = true">
                <BaseIcon name="plus" :size="14" />
                <span>注册</span>
              </button>
            </div>
          </div>
          <div class="repo-picker__list">
            <div v-if="state.repos.length === 0" class="empty" style="padding:20px;">
              <span class="empty__desc">暂无仓库，点击「注册」添加</span>
            </div>
            <div
              v-for="r in state.repos"
              :key="r.id"
              class="repo-picker__item"
              :class="{ 'is-selected': reviewRepoId === String(r.id) }"
              @click="selectRepo(r)"
              :title="r.url"
            >
              <span class="repo-status__dot" :data-status="r.status || 'UNCLONED'" style="width:6px;height:6px;border-radius:50%;flex-shrink:0;"></span>
              <span class="repo-picker__name">{{ r.name }}</span>
              <span class="pill">{{ r.type || 'REMOTE' }}</span>
              <span class="repo-status" :data-status="r.status || 'UNCLONED'" style="font-size:11px;">
                {{ r.status || 'UNCLONED' }}
              </span>
            </div>
          </div>
        </div>

        <!-- 加载 commits 按钮 -->
        <div class="form-actions" style="margin-top:12px;">
          <button class="btn btn--ghost btn--sm" :disabled="!reviewRepoId" @click="loadCommits">
            <BaseIcon name="git-commit" :size="13" />
            <span>加载 commits</span>
          </button>
          <span v-if="selectedRepoName" style="font-size:12px;color:var(--fg-muted);align-self:center;">
            当前: <b style="color:var(--primary);">{{ selectedRepoName }}</b>
          </span>
        </div>

        <!-- commit 列表 -->
        <div class="commit-list" style="margin-top:8px;">
          <div v-if="commits.length === 0" class="empty" style="padding:20px;">
            <span class="empty__desc">{{ commitsLoaded ? '无 commit 记录' : '选择仓库并点击「加载 commits」' }}</span>
          </div>
          <div v-for="c in commits" :key="c.commitId"
            class="commit-item" :class="{ 'is-selected': selectedCommitId === c.commitId }"
            @click="selectCommit(c.commitId)">
            <div class="commit-item__id">{{ shortId(c.commitId) }}</div>
            <div class="commit-item__msg" :title="c.shortMessage || ''">{{ c.shortMessage || '(无提交信息)' }}</div>
            <div class="commit-item__time">{{ fmtTime(c.commitTime) }}</div>
          </div>
        </div>

        <!-- 手动填写 -->
        <div class="form-row" style="margin-top:16px;">
          <div class="field" style="flex:1;">
            <label class="field__label">手动 repoUrl</label>
            <input class="input" v-model="manualRepoUrl" placeholder="https://github.com/your/repo.git">
          </div>
          <div class="field" style="flex:0 0 220px;">
            <label class="field__label">手动 commitId</label>
            <input class="input" v-model="manualCommitId" placeholder="commit sha">
          </div>
        </div>

        <div class="form-actions">
          <button class="btn btn--primary" :disabled="starting" @click="startReview">
            <BaseIcon name="play" :size="13" />
            <span>{{ starting ? '启动中…' : '启动 Agent' }}</span>
          </button>
          <button class="btn btn--ghost" @click="resetReview">
            <BaseIcon name="refresh-cw" :size="13" />
            <span>重置</span>
          </button>
        </div>

        <!-- Agent 流程说明 -->
        <details class="details-collapse" style="margin-top:16px;">
          <summary>Agent 执行流程说明</summary>
          <div class="agent-flow">
            <p style="margin:0 0 8px; font-size:13px; color:var(--fg-secondary);">
              启动后 Agent 进入 <b style="color:var(--primary);">ReAct 循环</b>，按 thought → action → observation 迭代：
            </p>
            <ol class="agent-flow__list">
              <li><b>GitDiffTool</b> · 拉取目标 commit 的代码变更</li>
              <li><b>AstAnalysisTool</b> · AST 静态分析，定位潜在缺陷</li>
              <li><b>RagSearchTool</b> · RAG 检索 Java 编码规范与示例代码</li>
              <li><b>RuleMatchTool</b> · 批量规则匹配</li>
              <li><b>SimilarCodeTool</b> · 相似代码检索</li>
              <li><b>Finish</b> · 汇总结论，输出结构化审查报告</li>
            </ol>
            <p style="margin:8px 0 0; font-size:12px; color:var(--fg-muted);">
              右侧轨迹抽屉实时展示每一步的 Prompt、LLM 思考与工具返回。
            </p>
          </div>
        </details>
      </div>
    </div>

    <!-- ============== Card 2：任务状态 ============== -->
    <div class="card" v-if="state.currentTaskId && statusData">
      <header class="card__head">
        <div class="card__title">
          <span class="tag tag--blue">STATUS</span>
          <span>任务状态</span>
        </div>
        <span class="card__hint">{{ taskMeta }}</span>
      </header>
      <div class="card__body">
        <div class="status-grid">
          <div class="metric">
            <span class="metric__label">状态</span>
            <span class="status-tag" :data-status="statusData?.status || 'PENDING'">
              <span class="status-tag__dot"></span>
              {{ statusData?.status || 'PENDING' }}
            </span>
          </div>
          <div class="metric">
            <span class="metric__label">轮次</span>
            <span class="metric__value">{{ statusData?.currentRound ?? 0 }}</span>
          </div>
          <div class="metric">
            <span class="metric__label">步数</span>
            <span class="metric__value">{{ statusData?.totalSteps ?? 0 }}</span>
          </div>
          <div class="metric">
            <span class="metric__label">动作</span>
            <span class="metric__value metric__value--mono">{{ statusData?.latestStep?.action || '—' }}</span>
          </div>
        </div>

        <div class="progress" :data-status="statusData?.status || 'PENDING'">
          <div class="progress__bar" :style="{ width: progressPct + '%' }" :data-status="statusData?.status || 'PENDING'"></div>
        </div>

        <div class="latest-thought" v-if="statusData?.latestStep?.thought">
          <div class="latest-thought__label">
            <BaseIcon name="zap" :size="11" />
            <span>最新思考</span>
          </div>
          <p class="latest-thought__text">{{ statusData.latestStep.thought }}</p>
        </div>
      </div>
    </div>

    <!-- ============== Card 3：审查报告 ============== -->
    <div class="card" v-if="resultData || reportData">
      <header class="card__head">
        <div class="card__title">
          <span class="tag tag--green">REPORT</span>
          <span>审查报告</span>
        </div>
        <span class="card__hint">{{ reportMeta }}</span>
      </header>
      <div class="card__body">
        <!-- issue 统计条 -->
        <div class="issue-summary" v-if="reportData && issueCount > 0">
          <span v-for="s in issueStatsList" :key="s.sev"
            class="issue-summary__item" :data-sev="s.sev">
            <span class="issue-summary__dot"></span>
            <span>{{ SEV_LABEL[s.sev] }}</span>
            <span class="issue-summary__n">{{ s.count }}</span>
          </span>
        </div>

        <!-- issue 分组 -->
        <div v-if="reportData">
          <div v-if="issueGroupsList.length === 0" class="empty" style="padding:24px;">
            <span class="empty__icon"><BaseIcon name="inbox" :size="20" /></span>
            <span class="empty__title">未解析出结构化问题</span>
            <span class="empty__desc">请查看下方原始报告</span>
          </div>
          <details v-for="g in issueGroupsList" :key="g.sev"
            class="issue-group" open>
            <summary class="issue-group__head">
              <span class="issue-group__title" :data-sev="g.sev">
                <span class="issue-summary__dot"></span>
                {{ SEV_LABEL[g.sev] }} · {{ g.sev }}
              </span>
              <span class="issue-group__count">{{ g.list.length }}</span>
              <span class="issue-group__chev"><BaseIcon name="chevron-down" :size="14" /></span>
            </summary>
            <div class="issue-group__body">
              <div v-for="(it, i) in g.list" :key="i" class="issue-item">
                <div class="issue-item__loc">
                  <span v-if="it.filePath" class="issue-item__file">{{ it.filePath }}</span>
                  <span v-if="it.lineNumber != null" class="issue-item__line">L{{ it.lineNumber }}</span>
                  <span v-if="it.ruleType" class="issue-item__rule">{{ it.ruleType }}</span>
                </div>
                <div class="issue-item__msg">{{ it.message || '' }}</div>
                <div v-if="it.suggestion" class="issue-item__suggestion">{{ it.suggestion }}</div>
              </div>
            </div>
          </details>
        </div>

        <!-- 原始报告 -->
        <details class="details-collapse" style="margin-top:16px;">
          <summary>原始报告</summary>
          <pre class="raw-report">{{ resultText }}</pre>
        </details>
      </div>
      <footer class="card__foot">
        <button class="btn btn--ghost btn--sm" @click="reloadReport">
          <BaseIcon name="refresh-cw" :size="13" />
          <span>刷新报告</span>
        </button>
      </footer>
    </div>

    <!-- ============== Card 4：审查历史 ============== -->
    <div class="card">
      <header class="card__head">
        <div class="card__title">
          <span class="tag tag--gray">HISTORY</span>
          <span>审查历史</span>
        </div>
        <span class="card__hint">点击历史项可加载对应报告</span>
      </header>
      <div class="card__body">
        <div class="form-actions" style="margin-top:0;">
          <button class="btn btn--ghost btn--sm" :disabled="historyLoading" @click="loadHistory">
            <BaseIcon name="refresh-cw" :size="13" />
            <span>{{ historyLoading ? '加载中...' : '加载历史' }}</span>
          </button>
        </div>

        <div v-if="historyList.length === 0" class="empty" style="padding:32px;">
          <span class="empty__icon"><BaseIcon name="inbox" :size="20" /></span>
          <span class="empty__title">暂无审查记录</span>
          <span class="empty__desc">完成一次审查后，历史会显示在这里</span>
        </div>

        <div v-else style="display:flex; flex-direction:column; gap:8px; margin-top:12px;">
          <div v-for="r in historyList" :key="r.taskId" class="history-item" @click="loadHistoryReport(r.taskId)">
            <div class="history-item__sev">
              <template v-for="s in SEV_ORDER" :key="s">
                <span v-if="(r[s.toLowerCase() + 'Count'] ?? 0) > 0" :data-sev="s">
                  {{ s[0] }}{{ r[s.toLowerCase() + 'Count'] }}
                </span>
              </template>
              <span v-if="noSev(r)" style="color:var(--fg-subtle); padding:2px 6px;">—</span>
            </div>
            <div class="history-item__main">
              <div class="history-item__repo">{{ r.repoName || r.repoUrl || '未知仓库' }}</div>
              <div class="history-item__meta">
                {{ (r.commitId || '').slice(0, 8) }} · {{ r.issueCount ?? 0 }} 问题 · {{ r.totalSteps ?? 0 }} 步 · {{ fmtHistoryTime(r) }}
              </div>
            </div>
            <div class="status-tag" :data-status="r.status === 'SUCCESS' ? 'SUCCESS' : 'FAILED'">
              <span class="status-tag__dot"></span>
              {{ r.status }}
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ============== 注册仓库 Modal ============== -->
    <div v-if="showRepoModal" class="modal-mask" @click.self="showRepoModal = false">
      <div class="modal">
        <header class="modal__head">
          <h3 class="modal__title">注册仓库</h3>
          <button class="btn btn--ghost btn--icon btn--sm" @click="showRepoModal = false">
            <BaseIcon name="x" :size="16" />
          </button>
        </header>
        <div class="modal__body">
          <form @submit.prevent="submitRepo">
            <div class="field">
              <label class="field__label">仓库名称 <span style="color:var(--danger)">*</span></label>
              <input class="input" v-model="repoForm.name" placeholder="my-project" required>
            </div>
            <div class="field" style="margin-top:16px;">
              <label class="field__label">仓库地址 <span style="color:var(--danger)">*</span></label>
              <input class="input" v-model="repoForm.url" placeholder="https://github.com/your/repo.git" required>
            </div>
            <div class="form-row" style="margin-top:16px;">
              <div class="field" style="flex:0 0 140px;">
                <label class="field__label">类型</label>
                <select class="select" v-model="repoForm.type">
                  <option value="REMOTE">REMOTE</option>
                  <option value="LOCAL">LOCAL</option>
                </select>
              </div>
              <div class="field" style="flex:1;">
                <label class="field__label">默认分支</label>
                <input class="input" v-model="repoForm.defaultBranch" placeholder="main">
              </div>
            </div>
            <div class="form-actions">
              <button class="btn btn--ghost" type="button" @click="showRepoModal = false">取消</button>
              <button class="btn btn--primary" type="submit">
                <BaseIcon name="check" :size="14" />
                <span>注册</span>
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { ref, reactive, computed, watch, onUnmounted } from 'vue'
import { state, setConn, showToast, api, tryJson, fmtJson, fmtTime, shortId } from '../composables/useApi.js'
import BaseIcon from './BaseIcon.vue'

const SEV_ORDER = ['CRITICAL', 'MAJOR', 'MINOR', 'INFO']
const SEV_LABEL = { CRITICAL: '严重', MAJOR: '主要', MINOR: '次要', INFO: '提示' }

const emit = defineEmits(['traj-update'])

// ============== 组件内 ref 状态 ==============
const selectedCommitId = ref(null)
const commits = ref([])
const commitsLoaded = ref(false)
const manualRepoUrl = ref('')
const manualCommitId = ref('')
const reviewRepoId = ref('')
const statusData = ref(null)
const resultData = ref(null)
const reportData = ref(null)
const historyList = ref([])
const pollTimer = ref(null)
const trajTimer = ref(null)
const starting = ref(false)
const historyLoading = ref(false)
const taskMeta = ref('')

// 仓库注册 Modal
const showRepoModal = ref(false)
const repoForm = reactive({ name: '', url: '', type: 'REMOTE', defaultBranch: 'main' })

// 当前选中的仓库名称（展示用）
const selectedRepoName = computed(() => {
  if (!reviewRepoId.value) return ''
  const r = state.repos.find(x => String(x.id) === reviewRepoId.value)
  return r?.name || ''
})

// ============== 仓库选择 ==============
// 同步外部 selectedRepoId（兼容从仓库管理页跳转）
watch(() => state.selectedRepoId, (id) => {
  if (id != null) reviewRepoId.value = String(id)
}, { immediate: true })

function selectRepo(r) {
  reviewRepoId.value = String(r.id)
  state.selectedRepoId = r.id
  onRepoChange()
}

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

async function submitRepo() {
  try {
    setConn('running', 'SAVING')
    await api('/repo', { method: 'POST', body: JSON.stringify({ ...repoForm }) })
    showToast('注册成功', 'success')
    showRepoModal.value = false
    repoForm.name = ''; repoForm.url = ''; repoForm.type = 'REMOTE'; repoForm.defaultBranch = 'main'
    await loadRepos()
  } catch (e) {
    showToast('注册失败: ' + e.message, 'error')
    setConn('error', 'ERROR')
  }
}

function onRepoChange() {
  selectedCommitId.value = null
  manualCommitId.value = ''
  commits.value = []
  commitsLoaded.value = false
}

// ============== Commits ==============
async function loadCommits() {
  if (!reviewRepoId.value) { showToast('请先选择仓库', 'error'); return }
  try {
    setConn('running', 'LOADING COMMITS')
    commits.value = await api(`/repo/${reviewRepoId.value}/commits?limit=20`)
    commitsLoaded.value = true
    setConn('idle', 'API IDLE')
  } catch (e) {
    showToast('加载 commits 失败: ' + e.message, 'error')
    setConn('error', 'ERROR')
  }
}

function selectCommit(id) {
  selectedCommitId.value = id
  manualCommitId.value = id
}

// ============== 启动审查 ==============
async function startReview() {
  let repoUrl, commitId, repoName
  const repoId = reviewRepoId.value
  if (repoId && selectedCommitId.value) {
    commitId = selectedCommitId.value
  } else if (manualRepoUrl.value && manualCommitId.value) {
    repoUrl = manualRepoUrl.value.trim()
    commitId = manualCommitId.value.trim()
  } else {
    showToast('请选择仓库+commit，或手动填写 repoUrl+commitId', 'error')
    return
  }

  starting.value = true
  setConn('running', 'STARTING')
  state.trajMode = 'review'
  emit('traj-update', { steps: [], status: 'PENDING' })

  try {
    let data
    if (repoId && selectedCommitId.value) {
      data = await api('/review/by-repo', {
        method: 'POST',
        body: JSON.stringify({ repoId: Number(repoId), commitId }),
      })
      repoName = data.repoName
    } else {
      data = await api('/review/start', {
        method: 'POST',
        body: JSON.stringify({ repoUrl, commitId }),
      })
    }
    state.currentTaskId = data.taskId
    statusData.value = { status: 'PENDING', currentRound: 0, totalSteps: 0 }
    taskMeta.value = (repoName || repoUrl || '') + ' @ ' + commitId
    resultData.value = null
    reportData.value = null
    showToast('任务已提交, taskId=' + shortId(state.currentTaskId), 'success')
    state.terminalStatus = false
    startPolling()
  } catch (err) {
    showToast('启动失败: ' + err.message, 'error')
    setConn('error', 'ERROR')
  } finally {
    starting.value = false
  }
}

// ============== 重置 ==============
function resetReview() {
  stopPolling()
  state.currentTaskId = null
  state.terminalStatus = false
  statusData.value = null
  resultData.value = null
  reportData.value = null
  selectedCommitId.value = null
  manualRepoUrl.value = ''
  manualCommitId.value = ''
  commits.value = []
  commitsLoaded.value = false
  taskMeta.value = ''
  state.trajMode = 'review'
  emit('traj-update', { steps: [], status: 'PENDING' })
  setConn('idle', 'API IDLE')
}

// ============== 轮询 ==============
async function pollOnce() {
  if (!state.currentTaskId || state.terminalStatus) return
  try {
    const data = await api(`/review/${state.currentTaskId}/status`)
    statusData.value = data
    taskMeta.value = `${data.repoUrl || '—'} @ ${data.commitId || '—'}`
    setConn('running', `ROUND ${data.currentRound ?? 0}`)
    if (data.status === 'SUCCESS') {
      stopPolling(true)
      await loadFinalResult()
    } else if (data.status === 'FAILED') {
      stopPolling(false, data.errorMessage)
    }
  } catch (e) {
    showToast('状态查询失败: ' + e.message, 'error')
  }
}

async function pollTrajOnce() {
  if (!state.currentTaskId || state.terminalStatus || state.trajMode !== 'review') return
  try {
    const data = await api(`/review/${state.currentTaskId}/steps`)
    emit('traj-update', data)
  } catch (e) { /* 轨迹查询失败静默忽略 */ }
}

async function loadFinalResult() {
  try {
    const data = await api(`/review/${state.currentTaskId}/result`)
    resultData.value = data
    const traj = await api(`/review/${state.currentTaskId}/steps`)
    emit('traj-update', traj)
    await loadReport(state.currentTaskId)
  } catch (e) {
    showToast('获取结果失败: ' + e.message, 'error')
  }
}

async function loadReport(taskId) {
  try {
    const rep = await api(`/review/${taskId}/report`)
    reportData.value = rep
  } catch (e) {
    console.warn('加载 DB 报告失败，使用内存结果:', e.message)
  }
}

function startPolling() {
  if (pollTimer.value) { clearInterval(pollTimer.value); pollTimer.value = null }
  if (trajTimer.value) { clearInterval(trajTimer.value); trajTimer.value = null }
  state.terminalStatus = false
  pollOnce()
  pollTimer.value = setInterval(pollOnce, 1500)
  trajTimer.value = setInterval(pollTrajOnce, 2000)
}

function stopPolling(success, errMsg) {
  if (pollTimer.value) { clearInterval(pollTimer.value); pollTimer.value = null }
  if (trajTimer.value) { clearInterval(trajTimer.value); trajTimer.value = null }
  state.terminalStatus = true
  if (success) {
    setConn('success', 'COMPLETED')
  } else if (errMsg != null) {
    setConn('error', 'FAILED')
    showToast('审查失败: ' + (errMsg || '未知'), 'error')
  }
}

// ============== 报告 computeds ==============
const issueCount = computed(() => reportData.value?.issueCount ?? 0)

const issueStatsList = computed(() => {
  if (!reportData.value) return []
  return SEV_ORDER
    .map(s => ({ sev: s, count: reportData.value[s.toLowerCase() + 'Count'] ?? 0 }))
    .filter(x => x.count > 0)
})

const issueGroupsList = computed(() => {
  if (!reportData.value) return []
  const groups = reportData.value.issues || {}
  return SEV_ORDER
    .map(s => ({ sev: s, list: groups[s] || [] }))
    .filter(x => x.list.length > 0)
})

const reportMeta = computed(() => {
  if (reportData.value) {
    const rep = reportData.value
    const cost = rep.totalCostMs != null ? `${(rep.totalCostMs / 1000).toFixed(1)}s` : '—'
    return `${rep.issueCount ?? 0} 个问题 · ${rep.totalSteps ?? 0} 步 · ${cost}`
  }
  if (resultData.value) {
    return `共 ${resultData.value.totalSteps ?? 0} 步`
  }
  return ''
})

const resultText = computed(() => {
  let txt = '(空)'
  if (reportData.value) txt = reportData.value.finalResult || '(空)'
  else if (resultData.value) txt = resultData.value.result || '(空)'
  const parsed = tryJson(txt)
  return parsed ? fmtJson(parsed) : txt
})

const progressPct = computed(() => {
  const status = statusData.value?.status || 'PENDING'
  if (status === 'SUCCESS' || status === 'FAILED') return 100
  if (status === 'RUNNING') {
    return Math.min(90, 10 + (statusData.value?.currentRound || 0) * 12 + (statusData.value?.totalSteps || 0) * 5)
  }
  return 5
})

async function reloadReport() {
  if (!state.currentTaskId) return
  showToast('刷新报告中...')
  await loadReport(state.currentTaskId)
}

// ============== 审查历史 ==============
async function loadHistory() {
  try {
    historyLoading.value = true
    historyList.value = await api('/review/history')
  } catch (e) {
    showToast('加载历史失败: ' + e.message, 'error')
  } finally {
    historyLoading.value = false
  }
}

async function loadHistoryReport(taskId) {
  state.currentTaskId = taskId
  statusData.value = null
  resultData.value = null
  reportData.value = null
  await loadReport(taskId)
  try {
    const traj = await api(`/review/${taskId}/steps`)
    state.trajMode = 'review'
    emit('traj-update', traj)
  } catch (_) { /* 历史轨迹不可用，忽略 */ }
}

function fmtHistoryTime(r) {
  return r.completedAt ? new Date(r.completedAt).toLocaleString('zh-CN') : (r.createdAt || '—')
}

function noSev(r) {
  return SEV_ORDER.every(s => (r[s.toLowerCase() + 'Count'] ?? 0) === 0)
}

onUnmounted(() => {
  if (pollTimer.value) clearInterval(pollTimer.value)
  if (trajTimer.value) clearInterval(trajTimer.value)
})
</script>

<style scoped>
/* 仓库选择列表 */
.repo-picker{
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--bg);
}
.repo-picker__head{
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px var(--sp-3);
  border-bottom: 1px solid var(--border);
  background: var(--bg-subtle);
  border-radius: var(--r-sm) var(--r-sm) 0 0;
}
.repo-picker__actions{ display: flex; gap: 6px; }
.repo-picker__list{
  max-height: 200px; overflow-y: auto;
  padding: 4px;
}
.repo-picker__item{
  display: flex; align-items: center; gap: var(--sp-2);
  padding: 8px var(--sp-3);
  border-radius: var(--r-sm);
  cursor: pointer;
  transition: all var(--transition);
  font-size: 13px;
}
.repo-picker__item:hover{ background: var(--bg-hover); }
.repo-picker__item.is-selected{
  background: var(--primary-bg);
  color: var(--primary);
}
.repo-picker__item.is-selected .repo-picker__name{ color: var(--primary); font-weight: 500; }
.repo-picker__name{
  flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  color: var(--fg); font-weight: 500;
}
</style>
