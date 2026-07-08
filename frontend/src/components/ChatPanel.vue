<template>
  <section>
    <div class="page-head">
      <div>
        <h2 class="page-head__title">智能问答</h2>
        <p class="page-head__desc">Router 自动分类 · RAG 检索 · 流式回复 · 多轮记忆</p>
      </div>
      <div class="page-head__actions">
        <button class="btn btn--ghost btn--sm" @click="showSessionList = !showSessionList">
          <BaseIcon name="list" :size="13" />
          <span>历史会话</span>
        </button>
        <button class="btn btn--ghost btn--sm" @click="newConversation">
          <BaseIcon name="plus" :size="14" />
          <span>新对话</span>
        </button>
      </div>
    </div>

    <!-- 会话列表抽屉（折叠式） -->
    <div class="chat-sessions" v-if="showSessionList">
      <div class="chat-sessions__head">
        <span class="chat-sessions__title">历史会话</span>
        <button class="btn btn--ghost btn--icon btn--sm" @click="showSessionList = false">
          <BaseIcon name="x" :size="14" />
        </button>
      </div>
      <div class="chat-sessions__list">
        <div v-if="sessions.length === 0" class="chat-sessions__empty">
          暂无历史会话
        </div>
        <div
          v-for="s in sessions"
          :key="s.sessionId"
          class="chat-session-item"
          :class="{ 'is-active': state.currentChatSessionId === s.sessionId }"
          @click="switchSession(s.sessionId)"
        >
          <div class="chat-session-item__main">
            <div class="chat-session-item__title">{{ s.title || '新对话' }}</div>
            <div class="chat-session-item__meta">
              {{ s.messageCount ?? 0 }} 条 · {{ fmtSessionTime(s.updatedAt) }}
            </div>
          </div>
          <button
            class="chat-session-item__del"
            title="删除"
            @click.stop="deleteSession(s.sessionId)"
          >
            <BaseIcon name="trash" :size="12" />
          </button>
        </div>
      </div>
    </div>

    <div class="chat-container">
      <!-- 消息列表 -->
      <div class="chat-box" ref="chatBoxRef">
        <!-- 空状态 -->
        <div v-if="messages.length === 0" class="chat-empty">
          <span class="chat-empty__icon">
            <BaseIcon name="message-square" :size="24" />
          </span>
          <h3 class="chat-empty__title">问点什么吧</h3>
          <p class="chat-empty__desc">支持闲聊、Java 规范问答、审查引导（Router 自动分类）</p>
          <p class="chat-empty__desc" style="margin-top:4px;">已开启多轮记忆，最多保留最近 10 轮上下文</p>
        </div>

        <!-- 单条消息 -->
        <div
          v-for="(m, i) in messages"
          :key="i"
          class="msg"
          :class="{ 'msg--user': m.role === 'user' }"
        >
          <div class="msg__bubble">
            <span>{{ m.text }}</span><span v-if="m.streaming" class="stream-cursor"></span>
            <!-- 引用列表 -->
            <div v-if="m.references && m.references.length > 0" class="chat-refs">
              <div class="chat-refs__head">
                <BaseIcon name="layers" :size="11" />
                <span>引用 ({{ m.references.length }})</span>
              </div>
              <div v-for="(r, ri) in m.references" :key="ri" class="chat-ref">
                <span class="chat-ref__kind" :data-kind="r.kind">{{ r.kind }}</span>
                <span class="chat-ref__text">{{ r.title }} · score={{ (r.score || 0).toFixed(3) }}{{ r.lineNumbers ? ' · 行 ' + r.lineNumbers : '' }}</span>
              </div>
            </div>
          </div>
          <!-- assistant 消息元信息 -->
          <div v-if="m.role === 'assistant'" class="msg__meta">
            <span v-if="!m.meta.routerType" class="pill">连接中…</span>
            <span v-if="m.meta.routerType" class="pill pill--blue">{{ m.meta.routerType }}</span>
            <span v-if="m.meta.routerCostMs != null" class="pill">
              <BaseIcon name="clock" :size="10" />
              router {{ m.meta.routerCostMs }}ms
            </span>
            <span v-if="m.meta.totalCostMs != null" class="pill">
              <BaseIcon name="clock" :size="10" />
              total {{ m.meta.totalCostMs }}ms
            </span>
          </div>
        </div>
      </div>

      <!-- 输入区 -->
      <form class="chat-input-row" @submit.prevent="sendMessage">
        <input
          class="input chat-input"
          v-model="inputText"
          placeholder="输入问题，回车发送"
        >
        <button class="btn btn--primary" type="submit" :disabled="sending">
          <BaseIcon name="send" :size="13" />
          <span>{{ sending ? '发送中…' : '发送' }}</span>
        </button>
      </form>
    </div>
  </section>
</template>

<script setup>
import { ref, nextTick, onMounted } from 'vue'
import { state, setConn, showToast, api, API_BASE, tryJson, setChatSessionId, clearChatSession, fmtTime } from '../composables/useApi.js'
import BaseIcon from './BaseIcon.vue'

const emit = defineEmits(['chat-traj'])

const messages = ref([])
const inputText = ref('')
const sending = ref(false)
const chatBoxRef = ref(null)

// 会话列表
const showSessionList = ref(false)
const sessions = ref([])

function scrollToBottom() {
  nextTick(() => {
    const el = chatBoxRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

// ============== 新对话 ==============
function newConversation() {
  clearChatSession()
  messages.value = []
  showSessionList.value = false
  showToast('已开启新对话', 'info')
}

// ============== 会话列表 ==============
async function loadSessions() {
  try {
    sessions.value = await api('/chat/sessions')
  } catch (e) {
    showToast('加载会话列表失败: ' + e.message, 'error')
  }
}

async function switchSession(sessionId) {
  if (state.currentChatSessionId === sessionId) {
    showSessionList.value = false
    return
  }
  try {
    const msgs = await api(`/chat/sessions/${sessionId}/messages`)
    setChatSessionId(sessionId)
    // 重建消息列表
    messages.value = msgs.map(m => ({
      role: m.role,
      text: m.content || '',
      meta: {
        routerType: m.routerType || '',
        routerCostMs: null,
        totalCostMs: m.costMs ?? null,
      },
      references: parseRefs(m.references),
      streaming: false,
    }))
    showSessionList.value = false
    state.trajMode = 'chat'
    showToast(`已加载会话（${msgs.length} 条消息）`, 'success')
    scrollToBottom()
  } catch (e) {
    showToast('加载会话失败: ' + e.message, 'error')
  }
}

async function deleteSession(sessionId) {
  if (!confirm('确认删除该会话?')) return
  try {
    await api(`/chat/sessions/${sessionId}`, { method: 'DELETE' })
    showToast('已删除', 'success')
    if (state.currentChatSessionId === sessionId) {
      clearChatSession()
      messages.value = []
    }
    await loadSessions()
  } catch (e) {
    showToast('删除失败: ' + e.message, 'error')
  }
}

function parseRefs(refsStr) {
  if (!refsStr) return []
  try {
    const arr = JSON.parse(refsStr)
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}

function fmtSessionTime(s) {
  if (!s) return ''
  try {
    const d = new Date(s)
    return (d.getMonth() + 1) + '/' + d.getDate() + ' ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0')
  } catch { return '' }
}

// ============== 清空对话（保留 sessionId） ==============
function clearChat() {
  messages.value = []
  showToast('对话已清空（会话保留）', 'info')
}

// ============== 发送消息 ==============
async function sendMessage() {
  const msg = inputText.value.trim()
  if (!msg || sending.value) return

  messages.value.push({
    role: 'user',
    text: msg,
    meta: {},
    references: [],
    streaming: false,
  })
  inputText.value = ''
  sending.value = true
  setConn('running', 'CHAT')
  state.trajMode = 'chat'

  messages.value.push({
    role: 'assistant',
    text: '',
    meta: { routerType: '', routerCostMs: null, totalCostMs: null },
    references: [],
    streaming: true,
  })
  const cur = messages.value[messages.value.length - 1]
  scrollToBottom()

  try {
    const res = await fetch(API_BASE + '/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
      body: JSON.stringify({ message: msg, sessionId: state.currentChatSessionId }),
    })
    if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}`)

    const reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''

    while (true) {
      const { value, done: streamDone } = await reader.read()
      if (streamDone) break
      buffer += decoder.decode(value, { stream: true })

      let idx
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const chunk = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        const lines = chunk.split('\n')
        for (const line of lines) {
          if (!line.startsWith('data:')) continue
          const jsonStr = line.slice(5).trim()
          if (!jsonStr) continue
          const ev = tryJson(jsonStr)
          if (!ev) continue

          if (ev.type === 'META') {
            // 保存 sessionId（后端返回的可能是新建的会话）
            if (ev.sessionId) {
              setChatSessionId(ev.sessionId)
            }
            cur.meta.routerType = ev.routerType || ''
            if (ev.routerCostMs != null) cur.meta.routerCostMs = ev.routerCostMs
            if (ev.references) cur.references = ev.references
            emit('chat-traj', ev)
          } else if (ev.type === 'TOKEN') {
            cur.text += (ev.data || '')
          } else if (ev.type === 'DONE') {
            if (ev.totalCostMs != null) cur.meta.totalCostMs = ev.totalCostMs
            cur.streaming = false
          } else if (ev.type === 'ERROR') {
            cur.text += '\n[错误] ' + (ev.data || '未知错误')
            setConn('error', 'ERROR')
          }
          scrollToBottom()
        }
      }
    }

    cur.streaming = false
    setConn('idle', 'API IDLE')
  } catch (err) {
    cur.text = '调用失败: ' + err.message
    cur.streaming = false
    setConn('error', 'ERROR')
  } finally {
    sending.value = false
  }
}

// ============== 挂载时：如果有 sessionId，加载历史消息 ==============
onMounted(async () => {
  if (state.currentChatSessionId) {
    try {
      const msgs = await api(`/chat/sessions/${state.currentChatSessionId}/messages`)
      messages.value = msgs.map(m => ({
        role: m.role,
        text: m.content || '',
        meta: {
          routerType: m.routerType || '',
          routerCostMs: null,
          totalCostMs: m.costMs ?? null,
        },
        references: parseRefs(m.references),
        streaming: false,
      }))
      scrollToBottom()
    } catch (e) {
      // sessionId 失效，清空
      clearChatSession()
    }
  }
})

// 暴露方法给父组件（切换到 chat tab 时刷新会话列表）
defineExpose({ loadSessions })
</script>

<style scoped>
.chat-ref__text{
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

/* 会话列表 */
.chat-sessions{
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  margin-bottom: var(--sp-4);
  overflow: hidden;
}
.chat-sessions__head{
  display: flex; align-items: center; justify-content: space-between;
  padding: var(--sp-3) var(--sp-4);
  border-bottom: 1px solid var(--border);
  background: var(--bg-subtle);
}
.chat-sessions__title{
  font-size: 13px; font-weight: 600; color: var(--fg);
}
.chat-sessions__list{
  max-height: 280px; overflow-y: auto;
  padding: var(--sp-2);
}
.chat-sessions__empty{
  padding: var(--sp-4);
  text-align: center;
  font-size: 13px; color: var(--fg-muted);
}
.chat-session-item{
  display: flex; align-items: center; gap: var(--sp-2);
  padding: var(--sp-2) var(--sp-3);
  border-radius: var(--r-sm);
  cursor: pointer;
  transition: all var(--transition);
}
.chat-session-item:hover{ background: var(--bg-hover); }
.chat-session-item.is-active{
  background: var(--primary-bg);
}
.chat-session-item.is-active .chat-session-item__title{ color: var(--primary); }
.chat-session-item__main{ flex: 1; min-width: 0; }
.chat-session-item__title{
  font-size: 13px; font-weight: 500; color: var(--fg);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.chat-session-item__meta{
  font-size: 11px; color: var(--fg-muted);
  margin-top: 2px;
}
.chat-session-item__del{
  flex-shrink: 0;
  width: 24px; height: 24px;
  border: none; background: transparent;
  color: var(--fg-subtle);
  border-radius: var(--r-xs);
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: all var(--transition);
  opacity: 0;
}
.chat-session-item:hover .chat-session-item__del{ opacity: 1; }
.chat-session-item__del:hover{ background: var(--danger-bg); color: var(--danger); }
</style>
