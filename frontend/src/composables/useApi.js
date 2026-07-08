import { reactive, ref } from 'vue'

// 后端 API 基础路径（Vite proxy 代理 /api → localhost:8080，避免 CORS）
const API_BASE = '/api'

// ============== 全局状态（单例，所有组件共享） ==============
const state = reactive({
  // 连接状态
  connState: 'idle',
  connLabel: 'API IDLE',
  // Toast
  toastMsg: '',
  toastType: 'info',
  toastVisible: false,
  // 仓库
  repos: [],
  selectedRepoId: null,
  // 审查
  currentTaskId: null,
  terminalStatus: false,
  // 轨迹模式：'review' | 'chat'
  trajMode: 'review',
  // 侧边栏
  sideOpen: true,
  // 聊天会话
  currentChatSessionId: localStorage.getItem('chatSessionId') || null,
})

let toastTimer = null

// ============== 工具函数 ==============
function setConn(s, label) {
  state.connState = s
  state.connLabel = label
}

function showToast(msg, type = 'info') {
  state.toastMsg = msg
  state.toastType = type
  state.toastVisible = true
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { state.toastVisible = false }, 2600)
}

function tryJson(text) {
  if (!text) return null
  try { return JSON.parse(text) } catch { return null }
}

function fmtJson(obj) {
  try { return JSON.stringify(obj, null, 2) } catch { return String(obj) }
}

function escapeHtml(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
}

function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

function fmtTime(ms) {
  if (!ms) return ''
  const d = new Date(ms)
  return (d.getMonth() + 1) + '/' + d.getDate() + ' ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0')
}

function shortId(id) { return id ? id.slice(0, 8) : '' }

// ============== 聊天会话管理 ==============
/** 设置当前会话 ID 并持久化到 localStorage */
function setChatSessionId(id) {
  state.currentChatSessionId = id
  if (id) {
    localStorage.setItem('chatSessionId', id)
  } else {
    localStorage.removeItem('chatSessionId')
  }
}

/** 清空当前会话（新对话） */
function clearChatSession() {
  setChatSessionId(null)
}

// ============== API 请求封装 ==============
async function api(path, opts = {}) {
  const url = API_BASE + path
  const res = await fetch(url, {
    headers: { 'Content-Type': 'application/json' },
    ...opts,
  })
  let data
  try { data = await res.json() } catch { data = null }
  if (!res.ok || !data) throw new Error(`HTTP ${res.status} ${res.statusText}`)
  if (data.code !== 200) throw new Error(data.message || `业务错误 code=${data.code}`)
  return data.data
}

// 导出单例
export { state, setConn, showToast, tryJson, fmtJson, escapeHtml, esc, fmtTime, shortId, api, API_BASE, setChatSessionId, clearChatSession }
