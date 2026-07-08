<template>
  <section class="tab-panel is-active">
    <!-- ============== Card 1：发起审查 ============== -->
    <div class="card">
      <header class="card__head">
        <span class="tag">REVIEW</span>
        <h2 class="card__title">发起审查</h2>
        <span class="card__hint">选择仓库 + commit，或手动填写 repoUrl + commitId</span>
      </header>
      <div class="review-form">
        <!-- 仓库选择 + 加载 commits -->
        <div class="select-row">
          <label class="field" style="flex:1;">
            <span class="field__label">仓库</span>
            <select class="field__input field__select" v-model="reviewRepoId" @change="onRepoChange">
              <option value="">-- 请选择仓库 --</option>
              <option v-for="r in state.repos" :key="r.id" :value="String(r.id)">
                {{ r.name }} ({{ r.type || 'REMOTE' }})
              </option>
            </select>
          </label>
          <div class="field" style="flex:0 0 auto;">
            <button class="btn btn--ghost btn--sm" @click="loadCommits">加载 commits</button>
          </div>
        </div>

        <!-- commit 列表 -->
        <div class="commit-box">
          <div v-if="commits.length === 0" class="commit-empty">
            {{ commitsLoaded ? '无 commit 记录' : '选择仓库并点击"加载 commits"' }}
          </div>
          <div v-for="c in commits" :key="c.commitId"
            class="commit-item" :class="{ 'is-selected': selectedCommitId === c.commitId }"
            @click="selectCommit(c.commitId)">
            <div class="commit-item__id">{{ shortId(c.commitId) }}</div>
            <div class="commit-item__msg" :title="c.shortMessage || ''">{{ c.shortMessage || '' }}</div>
            <div class="commit-item__time">{{ fmtTime(c.commitTime) }}</div>
          </div>
        </div>

        <!-- 手动填写 -->
        <div class="select-row">
          <label class="field" style="flex:1;">
            <span class="field__label">手动 repoUrl</span>
            <input class="field__input" v-model="manualRepoUrl" placeholder="https://github.com/your/repo.git">
          </label>
          <label class="field" style="flex:0 0 220px;">
            <span class="field__label">手动 commitId</span>
            <input class="field__input" v-model="manualCommitId">
          </label>
        </div>

        <div class="form__actions">
          <button class="btn btn--primary" :disabled="starting" @click="startReview">
            <span class="btn__txt">{{ starting ? '启动中…' : '启动 Agent' }}</span>
            <span class="btn__arrow">→</span>
          </button>
          <button class="btn btn--ghost" @click="resetReview">重置</button>
        </div>

        <!-- Agent 启动说明 -->
        <details class="agent-explainer">
          <summary>Agent 执行流程说明</summary>
          <div class="agent-explainer__body">
            <p>启动后 Agent 进入 <b>ReAct 循环</b>，按 thought → action → observation 迭代：</p>
            <ol>
              <li><b>GitDiffTool</b> · 拉取目标 commit 的代码变更</li>
              <li><b>AstAnalysisTool</b> · AST 静态分析，定位潜在缺陷</li>
              <li><b>RagSearchTool</b> · RAG 检索 Java 编码规范与示例代码</li>
              <li><b>Finish</b> · 汇总结论，输出结构化审查报告</li>
            </ol>
            <p>右侧轨迹面板实时展示每一步的 Prompt、LLM 思考与工具返回。</p>
          </div>
        </details>
      </div>
    </div>

    <!-- ============== Card 2：任务状态（历史报告查看时隐藏） ============== -->
    <div class="card" v-show="state.currentTaskId && statusData">
      <header class="card__head">
        <span class="tag">STATUS</span>
        <h2 class="card__title">任务状态</h2>
        <span class="card__hint">{{ taskMeta }}</span>
      </header>
      <div class="status-grid">
        <div class="metric">
          <span class="metric__label">状态</span>
          <span class="metric__value metric__value--status" :data-status="statusData?.status || 'PENDING'">
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
      <div class="progress">
        <div class="progress__bar" :style="{ width: progressPct + '%', background: progressColor }"></div>
      </div>
      <div class="latest" v-if="statusData?.latestStep?.thought">
        <span class="latest__cap">最新思考</span>
        <p class="latest__txt">{{ statusData.latestStep.thought }}</p>
      </div>
    </div>

    <!-- ============== Card 3：审查报告 ============== -->
    <div class="card" v-show="resultData || reportData">
      <header class="card__head">
        <span class="tag">REPORT</span>
        <h2 class="card__title">审查报告</h2>
        <span class="card__hint">{{ reportMeta }}</span>
      </header>

      <!-- issue 统计条 -->
      <div class="issue-stats" v-if="reportData && issueCount > 0">
        <span v-for="s in issueStatsList" :key="s.sev"
          class="issue-stat" :class="'sev--' + s.sev">
          <span class="issue-stat__dot"></span>{{ SEV_LABEL[s.sev] }}
          <span class="issue-stat__n">{{ s.count }}</span>
        </span>
      </div>

      <!-- issue 分组（details/summary 折叠） -->
      <div class="issue-groups" v-if="reportData">
        <div v-if="issueGroupsList.length === 0" class="issue-empty">
          未解析出结构化问题（查看下方原始报告）
        </div>
        <details v-for="g in issueGroupsList" :key="g.sev"
          class="issue-group" :class="'sev--' + g.sev" open>
          <summary class="issue-group__head">
            <span class="issue-group__title" :class="'sev--' + g.sev">{{ SEV_LABEL[g.sev] }} · {{ g.sev }}</span>
            <span class="issue-group__count">{{ g.list.length }}</span>
            <span class="issue-group__chev"></span>
          </summary>
          <div class="issue-group__body">
            <div v-for="(it, i) in g.list" :key="i" class="issue-item">
              <div class="issue-item__loc">
                <span v-if="it.filePath" class="issue-item__file">{{ it.filePath }}</span>
                <span v-if="it.lineNumber != null" class="issue-item__line">L{{ it.lineNumber }}</span>
                <span v-if="it.ruleType" class="issue-item__rule">{{ it.ruleType }}</span>
              </div>
              <div class="issue-item__msg">{{ it.message || '' }}</div>
              <div v-if="it.suggestion" class="issue-item__sug">{{ it.suggestion }}</div>
            </div>
          </div>
        </details>
      </div>

      <!-- 原始报告（details/summary 折叠） -->
      <details class="result-raw">
        <summary>原始报告</summary>
        <div class="result">{{ resultText }}</div>
      </details>

      <div class="form__actions">
        <button class="btn btn--ghost btn--sm" @click="reloadReport">刷新报告</button>
      </div>
    </div>

    <!-- ============== Card 4：审查历史 ============== -->
    <div class="card">
      <header class="card__head">
        <span class="tag">HISTORY</span>
        <h2 class="card__title">审查历史</h2>
        <span class="card__hint">点击历史项可加载对应报告</span>
      </header>
      <div class="form__actions">
        <button class="btn btn--ghost btn--sm" :disabled="historyLoading" @click="loadHistory">
          {{ historyLoading ? '加载中...' : '加载历史' }}
        </button>
      </div>
      <div class="history-list">
        <p v-if="historyList.length === 0" class="empty-hint">暂无审查记录</p>
        <div v-for="r in historyList" :key="r.taskId" class="history-item" @click="loadHistoryReport(r.taskId)">
          <div class="history-item__sev">
            <template v-for="s in SEV_ORDER" :key="s">
              <span v-if="(r[s.toLowerCase() + 'Count'] ?? 0) > 0"
                :class="'sev--' + s" style="color:var(--fg)">
                {{ s[0] }}{{ r[s.toLowerCase() + 'Count'] }}
              </span>
            </template>
            <span v-if="noSev(r)" style="color:var(--fg-mute)">—</span>
          </div>
          <div class="history-item__main">
            <div class="history-item__repo">{{ r.repoName || r.repoUrl || '未知仓库' }}</div>
            <div class="history-item__meta">
              {{ (r.commitId || '').slice(0, 8) }} · {{ r.issueCount ?? 0 }} 问题 · {{ r.totalSteps ?? 0 }} 步 · {{ fmtHistoryTime(r) }}
            </div>
          </div>
          <div class="history-item__status"
            :class="r.status === 'SUCCESS' ? 'sev--INFO' : 'sev--CRITICAL'">
            {{ r.status }}
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { ref, computed, watch, onUnmounted } from 'vue'
import { state, setConn, showToast, api, tryJson, fmtJson, esc, fmtTime, shortId } from '../composables/useApi.js'

// 严重度分组配置
const SEV_ORDER = ['CRITICAL', 'MAJOR', 'MINOR', 'INFO']
const SEV_LABEL = { CRITICAL: '严重', MAJOR: '主要', MINOR: '次要', INFO: '提示' }

// 向父组件抛出轨迹更新事件（父组件转发到 TrajectoryPanel）
const emit = defineEmits(['traj-update'])

// ============== 组件内 ref 状态 ==============
const selectedCommitId = ref(null)   // 当前选中的 commit
const commits = ref([])              // commit 列表
const commitsLoaded = ref(false)     // 是否已加载过 commit（区分空态文案）
const manualRepoUrl = ref('')        // 手动填写的 repoUrl
const manualCommitId = ref('')       // 手动填写的 commitId
const reviewRepoId = ref('')         // 仓库 select 绑定（字符串 id）
const statusData = ref(null)         // /review/{taskId}/status 返回
const resultData = ref(null)         // /review/{taskId}/result 返回（内存结果）
const reportData = ref(null)         // /review/{taskId}/report 返回（DB 结构化报告）
const historyList = ref([])          // /review/history 返回
const pollTimer = ref(null)          // 状态轮询定时器
const trajTimer = ref(null)          // 轨迹轮询定时器
// 辅助状态
const starting = ref(false)          // 启动按钮 loading
const historyLoading = ref(false)    // 历史按钮 loading
const taskMeta = ref('')             // 状态卡片头部仓库@commit 文案

// ============== 仓库选择同步 ==============
// 侧边栏选中仓库时，同步到本组件的 select
watch(() => state.selectedRepoId, (id) => {
  if (id != null) reviewRepoId.value = String(id)
}, { immediate: true })

// 切换仓库时清空 commit 列表与已选 commit
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
    // 走 by-repo
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
  // 重置轨迹面板（通知父组件清空）
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
    // 初始化状态展示
    statusData.value = { status: 'PENDING', currentRound: 0, totalSteps: 0 }
    taskMeta.value = (repoName || repoUrl || '') + ' @ ' + commitId
    // 清空旧报告/结果
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
  // 通知轨迹面板清空
  emit('traj-update', { steps: [], status: 'PENDING' })
  setConn('idle', 'API IDLE')
}

// ============== 轮询：状态 + 轨迹 ==============
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

// 成功后加载内存结果 + 轨迹 + DB 报告
async function loadFinalResult() {
  try {
    const data = await api(`/review/${state.currentTaskId}/result`)
    resultData.value = data
    const traj = await api(`/review/${state.currentTaskId}/steps`)
    emit('traj-update', traj)
    // 持久化报告可能比内存结果晚写入（异步落库），延迟拉取 DB 结构化报告
    await loadReport(state.currentTaskId)
  } catch (e) {
    showToast('获取结果失败: ' + e.message, 'error')
  }
}

// 从 DB 拉取结构化报告（含 issue 分组），失败则降级到内存结果展示
async function loadReport(taskId) {
  try {
    const rep = await api(`/review/${taskId}/report`)
    reportData.value = rep
  } catch (e) {
    // 报告尚未落库或 DB 异常，保持内存结果展示，不报错
    console.warn('加载 DB 报告失败，使用内存结果:', e.message)
  }
}

function startPolling() {
  // 只清理定时器，不设置 terminalStatus（stopPolling 会设 true 导致轮询被跳过）
  if (pollTimer.value) { clearInterval(pollTimer.value); pollTimer.value = null }
  if (trajTimer.value) { clearInterval(trajTimer.value); trajTimer.value = null }
  state.terminalStatus = false  // 重置终止标志
  pollOnce()                    // 立即执行一次
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

// 统计条数据（仅保留 count > 0 的严重度）
const issueStatsList = computed(() => {
  if (!reportData.value) return []
  return SEV_ORDER
    .map(s => ({ sev: s, count: reportData.value[s.toLowerCase() + 'Count'] ?? 0 }))
    .filter(x => x.count > 0)
})

// 分组数据（仅保留有 issue 的严重度）
const issueGroupsList = computed(() => {
  if (!reportData.value) return []
  const groups = reportData.value.issues || {}
  return SEV_ORDER
    .map(s => ({ sev: s, list: groups[s] || [] }))
    .filter(x => x.list.length > 0)
})

// 报告头部 meta 文案：优先 DB 报告，降级内存结果
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

// 原始报告文本：优先 DB 报告 finalResult，降级内存 result，尝试 JSON 美化
const resultText = computed(() => {
  let txt = '(空)'
  if (reportData.value) txt = reportData.value.finalResult || '(空)'
  else if (resultData.value) txt = resultData.value.result || '(空)'
  const parsed = tryJson(txt)
  return parsed ? fmtJson(parsed) : txt
})

// 进度条百分比
const progressPct = computed(() => {
  const status = statusData.value?.status || 'PENDING'
  if (status === 'SUCCESS' || status === 'FAILED') return 100
  if (status === 'RUNNING') {
    return Math.min(90, 10 + (statusData.value?.currentRound || 0) * 12 + (statusData.value?.totalSteps || 0) * 5)
  }
  return 5
})

// 进度条颜色
const progressColor = computed(() => {
  const status = statusData.value?.status || 'PENDING'
  if (status === 'FAILED') return 'linear-gradient(90deg, #b80040, #d93b4a)'
  if (status === 'SUCCESS') return 'linear-gradient(90deg, #6aa6ff, #2f7cf6)'
  return 'linear-gradient(90deg, #b87d20, #e08a1f)'
})

// 刷新报告
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

// 点击历史项：加载对应报告 + 轨迹
async function loadHistoryReport(taskId) {
  state.currentTaskId = taskId
  // 历史报告展示在报告卡片，隐藏状态卡片
  statusData.value = null
  resultData.value = null
  reportData.value = null
  await loadReport(taskId)
  // 同时加载轨迹（历史任务内存可能已丢，失败忽略）
  try {
    const traj = await api(`/review/${taskId}/steps`)
    state.trajMode = 'review'
    emit('traj-update', traj)
  } catch (_) { /* 历史轨迹不可用，忽略 */ }
}

// 历史项时间格式化
function fmtHistoryTime(r) {
  return r.completedAt ? new Date(r.completedAt).toLocaleString('zh-CN') : (r.createdAt || '—')
}

// 历史项是否无任何严重度计数
function noSev(r) {
  return SEV_ORDER.every(s => (r[s.toLowerCase() + 'Count'] ?? 0) === 0)
}

// ============== 组件卸载时清理定时器 ==============
onUnmounted(() => {
  if (pollTimer.value) clearInterval(pollTimer.value)
  if (trajTimer.value) clearInterval(trajTimer.value)
})
</script>
