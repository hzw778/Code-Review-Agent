/* =========================================================
   Code Review Agent · Console App v1.1
   三栏布局：仓库侧边栏 + 主区(Tab) + 轨迹面板
   ========================================================= */

const API_BASE = 'http://localhost:8080';

const $ = (id) => document.getElementById(id);

// DOM refs
const tabs = $('tabs');
const connBadge = $('connBadge');
const toggleBtn = $('toggleTrajectory');
const sidePane = $('sidePane');
const closeSide = $('closeSide');
const toast = $('toast');

// Repo
const repoList = $('repoList');
const refreshRepos = $('refreshRepos');
const addRepoBtn = $('addRepoBtn');
const repoModal = $('repoModal');
const repoModalBody = $('repoModalBody');
const closeRepoModal = $('closeRepoModal');
const repoForm = $('repoForm');
const repoTable = $('repoTable');

// Review
const reviewRepoSelect = $('reviewRepoSelect');
const loadCommitsBtn = $('loadCommitsBtn');
const commitBox = $('commitBox');
const manualRepoUrl = $('manualRepoUrl');
const manualCommitId = $('manualCommitId');
const startReviewBtn = $('startReviewBtn');
const resetReviewBtn = $('resetReviewBtn');
const statusCard = $('statusCard');
const mStatus = $('mStatus');
const mRound = $('mRound');
const mSteps = $('mSteps');
const mAction = $('mAction');
const taskMeta = $('taskMeta');
const progressBar = $('progressBar');
const latestThought = $('latestThought');
const latestTxt = $('latestTxt');
const resultCard = $('resultCard');
const resultBody = $('resultBody');
const resultMeta = $('resultMeta');

// Chat
const chatBox = $('chatBox');
const chatForm = $('chatForm');
const chatInput = $('chatInput');
const chatSendBtn = $('chatSendBtn');
const chatClearBtn = $('chatClearBtn');

// Trajectory
const trajList = $('trajList');
const trajEmpty = $('trajEmpty');
const trajCount = $('trajCount');
const trajStatus = $('trajStatus');
const trajSubtitle = $('trajSubtitle');
const refreshTraj = $('refreshTraj');

// State
let repos = [];
let selectedRepoId = null;
let selectedCommitId = null;
let currentTaskId = null;
let pollTimer = null;
let trajTimer = null;
let lastTrajKey = ''; // 用于增量渲染
let lastTrajLen = 0;
let terminalStatus = false;
let currentTrajMode = 'review'; // 'review' | 'chat'

// Utils
function setConn(state, label){
  connBadge.dataset.state = state;
  connBadge.querySelector('.conn__label').textContent = label;
}
function showToast(msg, type='info'){
  toast.textContent = msg;
  toast.dataset.type = type;
  toast.hidden = false;
  toast.classList.add('show');
  clearTimeout(showToast._t);
  showToast._t = setTimeout(()=> toast.classList.remove('show'), 2600);
}
function tryJson(text){
  if(!text) return null;
  try{ return JSON.parse(text); }catch{ return null; }
}
function fmtJson(obj){ try{ return JSON.stringify(obj, null, 2); }catch{ return String(obj); } }
function escapeHtml(s){ return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
function fmtTime(ms){
  if(!ms) return '';
  const d = new Date(ms);
  return d.getMonth()+1 + '/' + d.getDate() + ' ' + String(d.getHours()).padStart(2,'0') + ':' + String(d.getMinutes()).padStart(2,'0');
}
function shortId(id){ return id ? id.slice(0,8) : ''; }

// API
async function api(path, opts={}){
  const url = API_BASE + path;
  const res = await fetch(url, {
    headers: { 'Content-Type': 'application/json' },
    ...opts,
  });
  let data;
  try{ data = await res.json(); }catch{ data = null; }
  if(!res.ok || !data) throw new Error(`HTTP ${res.status} ${res.statusText}`);
  if(data.code !== 200) throw new Error(data.message || `业务错误 code=${data.code}`);
  return data.data;
}

// ============== Tabs ==============
tabs.addEventListener('click', (e)=>{
  const tab = e.target.closest('.tab');
  if(!tab) return;
  const name = tab.dataset.tab;
  tabs.querySelectorAll('.tab').forEach(t => t.classList.toggle('is-active', t === tab));
  document.querySelectorAll('.tab-panel').forEach(p => p.classList.toggle('is-active', p.dataset.tab === name));
});

// ============== Repo ==============
async function loadRepos(){
  try{
    setConn('running', 'LOADING');
    repos = await api('/repo');
    renderRepoList();
    renderRepoTable();
    renderReviewRepoSelect();
    setConn('idle', 'API IDLE');
  }catch(e){
    showToast('加载仓库失败: ' + e.message, 'error');
    setConn('error', 'ERROR');
  }
}

function renderRepoList(){
  if(repos.length === 0){
    repoList.innerHTML = '<div class="repo-empty">暂无仓库，点击上方注册</div>';
    return;
  }
  repoList.innerHTML = repos.map(r => `
    <div class="repo-item ${selectedRepoId === r.id ? 'is-selected' : ''}" data-id="${r.id}">
      <div class="repo-item__name">${escapeHtml(r.name)}</div>
      <div class="repo-item__url">${escapeHtml(r.url)}</div>
      <div class="repo-item__meta">
        <span class="pill">${r.type || 'REMOTE'}</span>
        <span class="pill" data-status="${r.status || 'UNCLONED'}">${r.status || 'UNCLONED'}</span>
      </div>
    </div>
  `).join('');
  repoList.querySelectorAll('.repo-item').forEach(el => {
    el.addEventListener('click', async ()=>{
      selectedRepoId = Number(el.dataset.id);
      const repo = repos.find(r => r.id === selectedRepoId);
      // 同步给审查页 select
      reviewRepoSelect.value = String(selectedRepoId);
      renderRepoList();
      // 切到审查页
      tabs.querySelector('[data-tab="review"]').click();
      showToast('已选择仓库: ' + (repo?.name || ''), 'success');
    });
  });
}

function renderReviewRepoSelect(){
  reviewRepoSelect.innerHTML = '<option value="">-- 请选择仓库 --</option>' +
    repos.map(r => `<option value="${r.id}">${escapeHtml(r.name)} (${r.type || 'REMOTE'})</option>`).join('');
  if(selectedRepoId) reviewRepoSelect.value = String(selectedRepoId);
}

// 切换仓库时清空 commit 列表和已选 commit
reviewRepoSelect.addEventListener('change', ()=>{
  selectedCommitId = null;
  manualCommitId.value = '';
  commitBox.innerHTML = '<div class="commit-empty">选择仓库并点击"加载 commits"</div>';
});

function renderRepoTable(){
  if(repos.length === 0){
    repoTable.innerHTML = '<div class="repo-empty">暂无仓库</div>';
    return;
  }
  repoTable.innerHTML = repos.map(r => `
    <div class="repo-row" data-id="${r.id}">
      <div class="repo-row__id">#${r.id}</div>
      <div class="repo-row__name">
        <div>${escapeHtml(r.name)}</div>
        <div>${escapeHtml(r.url)}</div>
      </div>
      <div class="repo-row__type">${r.type || 'REMOTE'}</div>
      <div class="repo-row__status" data-status="${r.status || 'UNCLONED'}">${r.status || 'UNCLONED'}</div>
      <div class="repo-row__actions">
        <button class="btn btn--ghost btn--sm" data-act="clone">克隆</button>
        <button class="btn btn--ghost btn--sm" data-act="review">审查</button>
        <button class="btn btn--ghost btn--sm" data-act="delete">删除</button>
      </div>
    </div>
  `).join('');
  repoTable.querySelectorAll('.repo-row').forEach(row => {
    const id = Number(row.dataset.id);
    row.querySelectorAll('button[data-act]').forEach(btn => {
      btn.addEventListener('click', async (e)=>{
        e.stopPropagation();
        const act = btn.dataset.act;
        if(act === 'clone'){
          await cloneRepo(id);
        }else if(act === 'review'){
          selectedRepoId = id;
          reviewRepoSelect.value = String(id);
          renderRepoList();
          tabs.querySelector('[data-tab="review"]').click();
        }else if(act === 'delete'){
          if(confirm('确认删除仓库?')) await deleteRepo(id);
        }
      });
    });
  });
}

async function cloneRepo(id){
  try{
    setConn('running', 'CLONING');
    showToast('开始克隆, 大仓库可能耗时较长...', 'info');
    await api(`/repo/${id}/clone`, { method: 'POST' });
    showToast('克隆成功', 'success');
    await loadRepos();
  }catch(e){
    showToast('克隆失败: ' + e.message, 'error');
    setConn('error', 'ERROR');
  }
}

async function deleteRepo(id){
  try{
    await api(`/repo/${id}`, { method: 'DELETE' });
    showToast('已删除', 'success');
    if(selectedRepoId === id) selectedRepoId = null;
    await loadRepos();
  }catch(e){
    showToast('删除失败: ' + e.message, 'error');
  }
}

// Register repo form (Repo Tab)
repoForm.addEventListener('submit', async (e)=>{
  e.preventDefault();
  const fd = new FormData(repoForm);
  const body = {
    name: fd.get('name'),
    url: fd.get('url'),
    type: fd.get('type'),
    defaultBranch: fd.get('defaultBranch') || 'main',
  };
  try{
    setConn('running', 'SAVING');
    await api('/repo', { method:'POST', body: JSON.stringify(body) });
    showToast('注册成功', 'success');
    repoForm.reset();
    await loadRepos();
  }catch(e){
    showToast('注册失败: ' + e.message, 'error');
    setConn('error', 'ERROR');
  }
});

// Quick add modal
addRepoBtn.addEventListener('click', ()=>{
  repoModalBody.innerHTML = `
    <form id="quickRepoForm" class="form">
      <label class="field"><span class="field__label">name</span>
        <input class="field__input" name="name" required></label>
      <label class="field"><span class="field__label">url</span>
        <input class="field__input" name="url" placeholder="https://github.com/your/repo.git" required></label>
      <div class="select-row">
        <label class="field" style="flex:0 0 140px;"><span class="field__label">type</span>
          <select class="field__input" name="type"><option value="REMOTE">REMOTE</option><option value="LOCAL">LOCAL</option></select></label>
        <label class="field" style="flex:1;"><span class="field__label">defaultBranch</span>
          <input class="field__input" name="defaultBranch" value="main"></label>
      </div>
      <div class="form__actions"><button class="btn btn--primary" type="submit">注册</button></div>
    </form>
  `;
  repoModal.hidden = false;
  const f = $('quickRepoForm');
  f.addEventListener('submit', async (ev)=>{
    ev.preventDefault();
    const fd = new FormData(f);
    const body = {
      name: fd.get('name'), url: fd.get('url'),
      type: fd.get('type'), defaultBranch: fd.get('defaultBranch') || 'main',
    };
    try{
      await api('/repo', { method:'POST', body: JSON.stringify(body) });
      showToast('注册成功', 'success');
      repoModal.hidden = true;
      await loadRepos();
    }catch(e){ showToast('注册失败: ' + e.message, 'error'); }
  });
});
closeRepoModal.addEventListener('click', ()=> repoModal.hidden = true);
repoModal.addEventListener('click', (e)=>{ if(e.target === repoModal) repoModal.hidden = true; });
refreshRepos.addEventListener('click', loadRepos);

// ============== Commits ==============
loadCommitsBtn.addEventListener('click', async ()=>{
  const repoId = reviewRepoSelect.value;
  if(!repoId){ showToast('请先选择仓库', 'error'); return; }
  try{
    setConn('running', 'LOADING COMMITS');
    const commits = await api(`/repo/${repoId}/commits?limit=20`);
    renderCommits(commits);
    setConn('idle', 'API IDLE');
  }catch(e){
    showToast('加载 commits 失败: ' + e.message, 'error');
    setConn('error', 'ERROR');
  }
});

function renderCommits(commits){
  if(!commits || commits.length === 0){
    commitBox.innerHTML = '<div class="commit-empty">无 commit 记录</div>';
    return;
  }
  commitBox.innerHTML = commits.map(c => `
    <div class="commit-item ${selectedCommitId === c.commitId ? 'is-selected' : ''}" data-id="${c.commitId}">
      <div class="commit-item__id">${shortId(c.commitId)}</div>
      <div class="commit-item__msg" title="${escapeHtml(c.shortMessage)}">${escapeHtml(c.shortMessage || '')}</div>
      <div class="commit-item__time">${fmtTime(c.commitTime)}</div>
    </div>
  `).join('');
  commitBox.querySelectorAll('.commit-item').forEach(el => {
    el.addEventListener('click', ()=>{
      selectedCommitId = el.dataset.id;
      commitBox.querySelectorAll('.commit-item').forEach(x => x.classList.toggle('is-selected', x === el));
      manualCommitId.value = selectedCommitId;
    });
  });
}

// ============== Review ==============
startReviewBtn.addEventListener('click', async ()=>{
  let repoUrl, commitId, repoName;
  const repoId = reviewRepoSelect.value;
  if(repoId && selectedCommitId){
    // 走 by-repo
    commitId = selectedCommitId;
  }else if(manualRepoUrl.value && manualCommitId.value){
    repoUrl = manualRepoUrl.value.trim();
    commitId = manualCommitId.value.trim();
  }else{
    showToast('请选择仓库+commit，或手动填写 repoUrl+commitId', 'error');
    return;
  }

  startReviewBtn.disabled = true;
  startReviewBtn.querySelector('.btn__txt').textContent = '启动中…';
  setConn('running', 'STARTING');
  resetTrajectory('review');

  try{
    let data;
    if(repoId && selectedCommitId){
      data = await api('/review/by-repo', {
        method:'POST',
        body: JSON.stringify({ repoId: Number(repoId), commitId }),
      });
      repoName = data.repoName;
    }else{
      data = await api('/review/start', {
        method:'POST',
        body: JSON.stringify({ repoUrl, commitId }),
      });
    }
    currentTaskId = data.taskId;
    statusCard.hidden = false;
    mStatus.textContent = 'PENDING';
    mStatus.dataset.status = 'PENDING';
    taskMeta.textContent = (repoName || repoUrl || '') + ' @ ' + commitId;
    showToast('任务已提交, taskId=' + shortId(currentTaskId), 'success');
    terminalStatus = false;
    startPolling();
  }catch(err){
    showToast('启动失败: ' + err.message, 'error');
    setConn('error', 'ERROR');
  }finally{
    startReviewBtn.disabled = false;
    startReviewBtn.querySelector('.btn__txt').textContent = '启动 Agent';
  }
});

resetReviewBtn.addEventListener('click', ()=>{
  formResetReview();
});

function formResetReview(){
  stopPolling();
  currentTaskId = null;
  terminalStatus = false;
  statusCard.hidden = true;
  resultCard.hidden = true;
  selectedCommitId = null;
  manualRepoUrl.value = '';
  manualCommitId.value = '';
  commitBox.innerHTML = '<div class="commit-empty">选择仓库并点击"加载 commits"</div>';
  resetTrajectory('review');
  setConn('idle', 'API IDLE');
}

// ============== Review polling ==============
async function pollOnce(){
  if(!currentTaskId || terminalStatus) return;
  try{
    const data = await api(`/review/${currentTaskId}/status`);
    renderStatus(data);
    setConn('running', `ROUND ${data.currentRound ?? 0}`);
    if(data.status === 'SUCCESS'){
      stopPolling(true);
      await loadFinalResult();
    }else if(data.status === 'FAILED'){
      stopPolling(false, data.errorMessage);
    }
  }catch(e){
    showToast('状态查询失败: ' + e.message, 'error');
  }
}

async function pollTrajOnce(){
  if(!currentTaskId || terminalStatus || currentTrajMode !== 'review') return;
  try{
    const data = await api(`/review/${currentTaskId}/steps`);
    renderTrajectoryReview(data);
  }catch(e){}
}

async function loadFinalResult(){
  try{
    const data = await api(`/review/${currentTaskId}/result`);
    renderResult(data);
    const traj = await api(`/review/${currentTaskId}/steps`);
    renderTrajectoryReview(traj);
  }catch(e){
    showToast('获取结果失败: ' + e.message, 'error');
  }
}

function startPolling(){
  // 只清理定时器，不设置 terminalStatus（stopPolling 会设 true 导致轮询被跳过）
  if(pollTimer){ clearInterval(pollTimer); pollTimer = null; }
  if(trajTimer){ clearInterval(trajTimer); trajTimer = null; }
  terminalStatus = false;  // 重置终止标志
  pollOnce();              // 立即执行一次
  pollTimer = setInterval(pollOnce, 1500);
  trajTimer = setInterval(pollTrajOnce, 2000);
}

function stopPolling(success, errMsg){
  if(pollTimer){ clearInterval(pollTimer); pollTimer = null; }
  if(trajTimer){ clearInterval(trajTimer); trajTimer = null; }
  terminalStatus = true;
  if(success){ setConn('success', 'COMPLETED'); }
  else if(errMsg != null){ setConn('error', 'FAILED'); showToast('审查失败: ' + (errMsg || '未知'), 'error'); }
}

function renderStatus(data){
  statusCard.hidden = false;
  const status = data.status || 'PENDING';
  mStatus.textContent = status;
  mStatus.dataset.status = status;
  mRound.textContent = data.currentRound ?? 0;
  mSteps.textContent = data.totalSteps ?? 0;
  const latest = data.latestStep;
  if(latest){
    mAction.textContent = latest.action || '—';
    if(latest.thought){
      latestThought.hidden = false;
      latestTxt.textContent = latest.thought;
    }else latestThought.hidden = true;
  }else{
    mAction.textContent = '—';
    latestThought.hidden = true;
  }
  taskMeta.textContent = `${data.repoUrl || '—'} @ ${data.commitId || '—'}`;
  let pct = 5;
  if(status === 'RUNNING') pct = Math.min(90, 10 + (data.currentRound || 0) * 12 + (data.totalSteps || 0) * 5);
  else if(status === 'SUCCESS' || status === 'FAILED') pct = 100;
  progressBar.style.width = pct + '%';
  if(status === 'FAILED') progressBar.style.background = 'linear-gradient(90deg, #b80040, #d93b4a)';
  else if(status === 'SUCCESS') progressBar.style.background = 'linear-gradient(90deg, #6aa6ff, #2f7cf6)';
  else progressBar.style.background = 'linear-gradient(90deg, #b87d20, #e08a1f)';
}

function renderResult(data){
  resultCard.hidden = false;
  resultMeta.textContent = `共 ${data.totalSteps ?? 0} 步`;
  let txt = data.result || '(空)';
  const parsed = tryJson(txt);
  if(parsed) txt = fmtJson(parsed);
  resultBody.textContent = txt;
}

// ============== Trajectory (Review mode) ==============
function renderTrajectoryReview(data){
  if(currentTrajMode !== 'review') return;
  trajSubtitle.textContent = 'ReAct 循环 · thought / action / observation';
  const steps = data.steps || [];
  trajCount.textContent = `${steps.length} 步`;
  trajStatus.textContent = data.status || '—';

  if(steps.length === 0){
    trajEmpty.style.display = '';
    trajList.querySelectorAll('.step').forEach(n => n.remove());
    lastTrajLen = 0;
    return;
  }
  trajEmpty.style.display = 'none';

  if(steps.length > lastTrajLen){
    for(let i = lastTrajLen; i < steps.length; i++){
      trajList.appendChild(buildReviewStep(steps[i], i === steps.length - 1));
    }
    lastTrajLen = steps.length;
    trajList.scrollTop = trajList.scrollHeight;
  }else if(steps.length < lastTrajLen){
    trajList.querySelectorAll('.step').forEach(n => n.remove());
    steps.forEach(s => trajList.appendChild(buildReviewStep(s, false)));
    lastTrajLen = steps.length;
  }
}

function buildReviewStep(step, autoExpand){
  const wrap = document.createElement('div');
  wrap.className = 'step';
  wrap.dataset.expanded = autoExpand ? 'true' : 'false';
  const action = step.action || '—';
  // 工具说明（中文）
  const toolDesc = TOOL_DESCRIPTIONS[action] || '';
  // observation 摘要
  const obsSummary = summarizeObservation(action, step.observation);
  wrap.innerHTML = `
    <div class="step__head">
      <span class="step__round">R${step.round ?? '?'}</span>
      <span class="step__action" data-tool="${escapeHtml(action)}">${escapeHtml(action)}</span>
      ${toolDesc ? `<span class="step__tooldesc">${escapeHtml(toolDesc)}</span>` : ''}
      <span class="step__cost">${step.costMs ?? 0} ms</span>
      <span class="step__chev">›</span>
    </div>
    <div class="step__body"></div>
    ${obsSummary ? `<div class="step__summary">${obsSummary}</div>` : ''}
  `;
  const body = wrap.querySelector('.step__body');
  // 发给 LLM 的完整 prompt
  if(step.promptSentToLlm) body.appendChild(buildBlock('input', '发给 LLM 的完整 Prompt', step.promptSentToLlm, false));
  // LLM 原始返回
  if(step.llmRawResponse) body.appendChild(buildBlock('obs', 'LLM 原始返回 (Raw Response)', step.llmRawResponse, false));
  // thought 块
  body.appendChild(buildBlock('thought', 'LLM 思考 (Thought)', step.thought, false));
  // actionInput 块（解析成 KV 表格）
  body.appendChild(buildActionInputBlock(step.actionInput));
  // observation 块
  body.appendChild(buildBlock('obs', '工具返回 (Observation)', step.observation, true));
  wrap.querySelector('.step__head').addEventListener('click', ()=>{
    wrap.dataset.expanded = wrap.dataset.expanded === 'true' ? 'false' : 'true';
  });
  return wrap;
}

/** 工具中文说明 */
const TOOL_DESCRIPTIONS = {
  'GitDiffTool': '拉取 commit 代码变更',
  'AstAnalysisTool': 'AST 静态分析',
  'RagSearchTool': 'RAG 规范检索',
  'Finish': '结束循环，输出结论',
};

/** 把 actionInput（JSON 字符串）解析成 KV 表格 */
function buildActionInputBlock(actionInput){
  const block = document.createElement('div');
  block.className = 'block block--input';
  const parsed = tryJson(actionInput);
  if(parsed && typeof parsed === 'object'){
    const rows = Object.entries(parsed).map(([k, v]) => {
      const valStr = typeof v === 'string' ? v : JSON.stringify(v);
      const display = valStr.length > 80 ? valStr.slice(0, 80) + '…' : valStr;
      return `<div class="kv"><span class="kv__k">${escapeHtml(k)}</span><span class="kv__v">${escapeHtml(display)}</span></div>`;
    }).join('');
    block.innerHTML = `
      <div class="block__head"><span class="block__dot"></span>工具参数 (ActionInput)</div>
      <div class="block__body kv-table">${rows}</div>
    `;
  }else{
    block.innerHTML = `
      <div class="block__head"><span class="block__dot"></span>工具参数 (ActionInput)</div>
      <div class="block__body">${escapeHtml(actionInput || '(空)')}</div>
    `;
  }
  return block;
}

/** 根据 action 类型，提取 observation 的关键字段做摘要 */
function summarizeObservation(action, observation){
  if(!observation) return '';
  const obj = tryJson(observation);
  if(!obj) return '';
  if(action === 'GitDiffTool'){
    if(obj.error) return `<span class="sum-tag sum-tag--err">失败</span> ${escapeHtml(obj.error)}`;
    return `<span class="sum-tag sum-tag--ok">变更 ${obj.totalFiles || 0} 文件</span> +${obj.totalAdditions || 0} -${obj.totalDeletions || 0}`;
  }
  if(action === 'AstAnalysisTool'){
    if(obj.error) return `<span class="sum-tag sum-tag--err">失败</span> ${escapeHtml(obj.error)}`;
    const issues = obj.issues || obj.results || [];
    return `<span class="sum-tag sum-tag--ok">检测到 ${Array.isArray(issues) ? issues.length : 0} 个问题</span>`;
  }
  if(action === 'RagSearchTool'){
    if(obj.error) return `<span class="sum-tag sum-tag--err">失败</span> ${escapeHtml(obj.error)}`;
    const rules = obj.rules || [];
    const codes = obj.codes || [];
    return `<span class="sum-tag sum-tag--ok">规则 ${Array.isArray(rules) ? rules.length : 0} 条</span> <span class="sum-tag sum-tag--ok">代码 ${Array.isArray(codes) ? codes.length : 0} 段</span>`;
  }
  return '';
}

function buildBlock(kind, label, content, tryJsonFmt){
  const block = document.createElement('div');
  block.className = `block block--${kind}`;
  let display = content == null || content === '' ? '(空)' : String(content);
  let isJson = false;
  if(tryJsonFmt && content){
    const parsed = tryJson(content);
    if(parsed){ display = fmtJson(parsed); isJson = true; }
  }
  block.innerHTML = `
    <div class="block__head"><span class="block__dot"></span>${escapeHtml(label)}</div>
    <div class="block__body${isJson ? ' is-json' : ''}">${escapeHtml(display)}</div>
  `;
  return block;
}

// ============== Chat ==============
chatClearBtn.addEventListener('click', ()=>{
  // 清空聊天框，恢复空状态
  chatBox.innerHTML = `
    <div class="chat-empty">
      <span class="chat-empty__mark">◐</span>
      <p>问点什么吧</p>
      <span>支持闲聊、Java 规范问答、审查引导（Router 自动分类）</span>
    </div>`;
  // 同时清空轨迹面板
  resetTrajectory('chat');
  showToast('对话已清空', 'info');
});

chatForm.addEventListener('submit', async (e)=>{
  e.preventDefault();
  const msg = chatInput.value.trim();
  if(!msg) return;
  appendUserMsg(msg);
  chatInput.value = '';
  chatSendBtn.disabled = true;
  setConn('running', 'CHAT');
  // 强制切换轨迹模式为 chat，确保 renderTrajectoryChat 能正常渲染
  currentTrajMode = 'chat';
  trajSubtitle.textContent = '数据传输链路 · Router → RAG → LLM';

  // 创建一个占位的 assistant bubble，流式追加
  const bubbleEl = appendStreamingAssistantMsg();

  try{
    const res = await fetch(API_BASE + '/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
      body: JSON.stringify({ message: msg }),
    });
    if(!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}`);

    // 读取 SSE 流
    const reader = res.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    let fullText = '';
    let meta = null;
    let done = null;

    while(true){
      const { value, done: streamDone } = await reader.read();
      if(streamDone) break;
      buffer += decoder.decode(value, { stream: true });

      // SSE 数据按 \n\n 分隔，每段含 "data: {...}"
      let idx;
      while((idx = buffer.indexOf('\n\n')) >= 0){
        const chunk = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 2);
        // 解析 "data: ..." 行
        const lines = chunk.split('\n');
        for(const line of lines){
          if(!line.startsWith('data:')) continue;
          const jsonStr = line.slice(5).trim();
          if(!jsonStr) continue;
          let ev;
          try { ev = JSON.parse(jsonStr); } catch { continue; }

          if(ev.type === 'META'){
            meta = ev;
            updateStreamingMeta(bubbleEl, ev);
            if(ev.references) renderChatRefs(bubbleEl, ev.references);
            // 确保 currentTrajMode 是 chat（防御性）
            currentTrajMode = 'chat';
            // META 事件先渲染 Router + RAG 步骤（LLM 步骤此时 costMs=0, status=streaming）
            if(ev.trace && ev.trace.length > 0){
              renderTrajectoryChat(ev);
            }
          }else if(ev.type === 'TOKEN'){
            fullText += (ev.data || '');
            updateStreamingBubble(bubbleEl, fullText);
          }else if(ev.type === 'DONE'){
            done = ev;
            // DONE 事件不重复渲染 trace（META 已渲染过），最终汇总在流结束时处理
          }else if(ev.type === 'ERROR'){
            fullText += '\n[错误] ' + (ev.data || '未知错误');
            updateStreamingBubble(bubbleEl, fullText);
            setConn('error', 'ERROR');
            // 错误也展示在轨迹里
            currentTrajMode = 'chat';
            renderTrajectoryChat({
              routerType: 'ERROR',
              trace: [{ stage: 'ERROR', detail: ev.data || '未知错误', costMs: 0 }]
            });
          }
        }
      }
    }

    // 流结束，补全 meta 信息
    finalizeStreamingMsg(bubbleEl, meta, done);
    setConn('idle', 'API IDLE');
  }catch(err){
    updateStreamingBubble(bubbleEl, '调用失败: ' + err.message);
    setConn('error', 'ERROR');
  }finally{
    chatSendBtn.disabled = false;
  }
});

/** 创建流式回复的占位 bubble，返回 textEl 供后续更新 */
function appendStreamingAssistantMsg(){
  const empty = chatBox.querySelector('.chat-empty');
  if(empty) empty.remove();
  const div = document.createElement('div');
  div.className = 'msg';
  div.innerHTML = `
    <div>
      <div class="msg__bubble"><span class="stream-cursor">▋</span></div>
      <div class="msg__meta"><span class="pill">连接中…</span></div>
    </div>
  `;
  chatBox.appendChild(div);
  chatBox.scrollTop = chatBox.scrollHeight;
  return {
    root: div,
    bubble: div.querySelector('.msg__bubble'),
    meta: div.querySelector('.msg__meta'),
  };
}

function updateStreamingBubble(el, text){
  el.bubble.innerHTML = escapeHtml(text) + '<span class="stream-cursor">▋</span>';
  chatBox.scrollTop = chatBox.scrollHeight;
}

function updateStreamingMeta(el, meta){
  const pills = [`<span class="pill pill--info">${meta.routerType || '?'}</span>`];
  if(meta.routerCostMs != null) pills.push(`<span class="pill">router ${meta.routerCostMs}ms</span>`);
  el.meta.innerHTML = pills.join('');
}

function renderChatRefs(el, references){
  if(!references || references.length === 0) return;
  const refsHtml = `
    <div class="chat-msg-refs">
      <div class="chat-msg-refs__head">引用 (${references.length})</div>
      ${references.map(r => `
        <div class="chat-msg-ref">
          <span class="chat-msg-ref__kind" data-kind="${r.kind}">${r.kind}</span>
          <span>${escapeHtml(r.title)} · score=${(r.score||0).toFixed(3)}</span>
        </div>`).join('')}
    </div>`;
  // 插到 bubble 末尾（在 cursor 之前）
  el.bubble.insertAdjacentHTML('beforeend', refsHtml);
}

function finalizeStreamingMsg(el, meta, done){
  // 移除光标
  const cursor = el.bubble.querySelector('.stream-cursor');
  if(cursor) cursor.remove();
  // 补 done 信息
  if(done && done.totalCostMs != null){
    el.meta.insertAdjacentHTML('beforeend', `<span class="pill">total ${done.totalCostMs}ms</span>`);
  }
}

function appendUserMsg(text){
  // 移除空状态
  const empty = chatBox.querySelector('.chat-empty');
  if(empty) empty.remove();
  const div = document.createElement('div');
  div.className = 'msg msg--user';
  div.innerHTML = `<div class="msg__bubble">${escapeHtml(text)}</div>`;
  chatBox.appendChild(div);
  chatBox.scrollTop = chatBox.scrollHeight;
}

function renderTrajectoryChat(data){
  if(currentTrajMode !== 'chat') return;
  trajSubtitle.textContent = '数据传输链路 · Router → RAG → LLM';
  const trace = data.trace || [];
  // 不再清空已有轨迹，改为追加。每轮对话的轨迹前面加一个分隔标记
  trajEmpty.style.display = 'none';

  // 如果是新的一轮对话（trace 重新从 Router 开始），加一个分隔线
  const existingSteps = trajList.querySelectorAll('.step').length;
  if(existingSteps > 0 && trace.length > 0 && trace[0].stage === 'Router'){
    const sep = document.createElement('div');
    sep.className = 'traj-sep';
    sep.textContent = `── 对话 ${Math.floor(existingSteps / 3) + 2} ──`;
    trajList.appendChild(sep);
  }

  // 更新总数
  const totalSteps = trajList.querySelectorAll('.step').length + trace.length;
  trajCount.textContent = `${totalSteps} 步`;
  trajStatus.textContent = data.routerType || '—';

  trace.forEach((t, i) => {
    const wrap = document.createElement('div');
    wrap.className = 'step';
    wrap.dataset.expanded = i === trace.length - 1 ? 'true' : 'false';
    const stage = t.stage || '—';
    const stageDesc = STAGE_DESCRIPTIONS[stage] || '';
    wrap.innerHTML = `
      <div class="step__head">
        <span class="step__round">${i+1}</span>
        <span class="step__action" data-stage="${escapeHtml(stage)}">${escapeHtml(stage)}</span>
        ${stageDesc ? `<span class="step__tooldesc">${escapeHtml(stageDesc)}</span>` : ''}
        <span class="step__cost">${t.costMs ?? 0} ms</span>
        <span class="step__chev">›</span>
      </div>
      <div class="step__body"></div>
    `;
    const body = wrap.querySelector('.step__body');

    // 渲染所有字段：基本信息 + 模型 + prompt + response + 检索结果 JSON
    body.appendChild(buildKvBlock('基本信息', {
      'stage': t.stage,
      'detail': t.detail,
      'costMs': t.costMs,
      'model': t.model,
      'status': t.status,
    }));

    if(t.userInput) body.appendChild(buildBlock('input', '用户输入 (userInput)', t.userInput, false));
    if(t.promptTemplate) body.appendChild(buildBlock('input', 'Prompt 模板 (promptTemplate)', t.promptTemplate, false));
    if(t.finalPrompt) body.appendChild(buildBlock('input', '最终 Prompt (finalPrompt)', t.finalPrompt, false));
    if(t.systemPrompt) body.appendChild(buildBlock('input', 'System Prompt', t.systemPrompt, false));
    if(t.userPrompt) body.appendChild(buildBlock('input', 'User Prompt', t.userPrompt, false));
    if(t.query) body.appendChild(buildBlock('input', '检索 Query', t.query, false));

    // 检索参数
    if(t.topK != null || t.similarityThreshold != null){
      body.appendChild(buildKvBlock('检索参数', {
        'topK': t.topK,
        'similarityThreshold': t.similarityThreshold,
        'ruleCount': t.ruleCount,
        'codeCount': t.codeCount,
      }));
    }

    // 原始响应
    if(t.rawResponse) body.appendChild(buildBlock('obs', 'LLM 原始响应 (rawResponse)', t.rawResponse, false));
    if(t.parsedType) body.appendChild(buildBlock('obs', '解析结果 (parsedType)', t.parsedType, false));

    // 检索结果完整 JSON
    if(t.rules) body.appendChild(buildBlock('obs', '规则检索结果 (rules JSON)', JSON.stringify(t.rules, null, 2), true));
    if(t.codes) body.appendChild(buildBlock('obs', '代码检索结果 (codes JSON)', JSON.stringify(t.codes, null, 2), true));

    wrap.querySelector('.step__head').addEventListener('click', ()=>{
      wrap.dataset.expanded = wrap.dataset.expanded === 'true' ? 'false' : 'true';
    });
    trajList.appendChild(wrap);
  });
  trajList.scrollTop = trajList.scrollHeight;
}

/** 阶段中文说明 */
const STAGE_DESCRIPTIONS = {
  'Router': 'LLM 分类（qwen-flash）',
  'RagSearchTool': '向量检索（embedding + ES KNN）',
  'LLM': '流式生成回复（glm-4.5-air）',
};

/** 构建 KV 表格块 */
function buildKvBlock(title, obj){
  const block = document.createElement('div');
  block.className = 'block block--input';
  const rows = Object.entries(obj)
    .filter(([_, v]) => v != null && v !== '')
    .map(([k, v]) => {
      const valStr = typeof v === 'object' ? JSON.stringify(v) : String(v);
      return `<div class="kv"><span class="kv__k">${escapeHtml(k)}</span><span class="kv__v">${escapeHtml(valStr)}</span></div>`;
    }).join('');
  block.innerHTML = `
    <div class="block__head"><span class="block__dot"></span>${escapeHtml(title)}</div>
    <div class="block__body kv-table">${rows}</div>
  `;
  return block;
}

// ============== Trajectory reset ==============
function resetTrajectory(mode){
  currentTrajMode = mode;
  lastTrajLen = 0;
  trajList.querySelectorAll('.step').forEach(n => n.remove());
  trajEmpty.style.display = '';
  trajCount.textContent = '0 步';
  trajStatus.textContent = '未启动';
  if(mode === 'review'){
    trajSubtitle.textContent = 'ReAct 循环 · thought / action / observation';
  }else{
    trajSubtitle.textContent = '数据传输链路 · Router → RAG → LLM';
  }
}

refreshTraj.addEventListener('click', async ()=>{
  if(currentTrajMode === 'review'){
    if(!currentTaskId){ showToast('当前没有审查任务', 'error'); return; }
    try{
      const data = await api(`/review/${currentTaskId}/steps`);
      renderTrajectoryReview(data);
    }catch(e){ showToast('刷新失败: ' + e.message, 'error'); }
  }else{
    showToast('聊天轨迹请在发送消息时查看', 'info');
  }
});

// ============== Side pane toggle ==============
function toggleSide(open){
  if(open == null) open = sidePane.dataset.open !== 'true';
  sidePane.dataset.open = open ? 'true' : 'false';
}
toggleBtn.addEventListener('click', ()=> toggleSide());
closeSide.addEventListener('click', ()=> toggleSide(false));

document.addEventListener('keydown', (e)=>{
  if((e.key === 't' || e.key === 'T')){
    if(document.activeElement && ['INPUT','TEXTAREA','SELECT'].includes(document.activeElement.tagName)) return;
    toggleSide();
  }
});

// ============== Init ==============
setConn('idle', 'API IDLE');
loadRepos();
