<template>
  <section class="tab-panel" data-tab="chat">
    <div class="card chat-card">
      <header class="card__head">
        <span class="tag">CHAT</span>
        <h2 class="card__title">智能问答</h2>
        <span class="card__hint">Router 自动分类 · RAG 检索 · 流式回复</span>
      </header>

      <!-- 消息列表 -->
      <div class="chat-box" ref="chatBoxRef">
        <!-- 空状态 -->
        <div v-if="messages.length === 0" class="chat-empty">
          <span class="chat-empty__mark">◐</span>
          <p>问点什么吧</p>
          <span>支持闲聊、Java 规范问答、审查引导（Router 自动分类）</span>
        </div>

        <!-- 单条消息：user 右对齐，assistant 左对齐 -->
        <div
          v-for="(m, i) in messages"
          :key="i"
          class="msg"
          :class="{ 'msg--user': m.role === 'user' }"
        >
          <div class="msg__bubble">{{ m.text }}<span v-if="m.streaming" class="stream-cursor">▋</span>
            <!-- 引用列表 -->
            <div v-if="m.references && m.references.length > 0" class="chat-msg-refs">
              <div class="chat-msg-refs__head">引用 ({{ m.references.length }})</div>
              <div v-for="(r, ri) in m.references" :key="ri" class="chat-msg-ref">
                <span class="chat-msg-ref__kind" :data-kind="r.kind">{{ r.kind }}</span>
                <span>{{ r.title }} · score={{ (r.score || 0).toFixed(3) }}{{ r.lineNumbers ? ' · 行 ' + r.lineNumbers : '' }}</span>
              </div>
            </div>
          </div>
          <!-- assistant 消息的元信息（routerType / 耗时） -->
          <div v-if="m.role === 'assistant'" class="msg__meta">
            <span v-if="!m.meta.routerType" class="pill">连接中…</span>
            <span v-if="m.meta.routerType" class="pill pill--info">{{ m.meta.routerType }}</span>
            <span v-if="m.meta.routerCostMs != null" class="pill">router {{ m.meta.routerCostMs }}ms</span>
            <span v-if="m.meta.totalCostMs != null" class="pill">total {{ m.meta.totalCostMs }}ms</span>
          </div>
        </div>
      </div>

      <!-- 输入区 -->
      <form class="chat-input-row" @submit.prevent="sendMessage">
        <input
          class="field__input chat-input"
          v-model="inputText"
          placeholder="输入问题，回车发送"
        >
        <button class="btn btn--primary" type="submit" :disabled="sending">
          {{ sending ? '发送中…' : '发送' }}
        </button>
        <button class="btn btn--ghost btn--sm" type="button" @click="clearChat">清空</button>
      </form>
    </div>
  </section>
</template>

<script setup>
import { ref, nextTick } from 'vue'
import { state, setConn, showToast, api, API_BASE, escapeHtml, tryJson } from '../composables/useApi.js'

// 通知父组件将 META 事件转发到轨迹面板（TrajectoryPanel）
const emit = defineEmits(['chat-traj'])

// 消息列表：每项 { role, text, meta, references, streaming }
//   - role: 'user' | 'assistant'
//   - text: 已累计的文本
//   - meta: { routerType, routerCostMs, totalCostMs }
//   - references: RAG 检索引用列表
//   - streaming: 是否仍在流式接收（控制光标显示）
const messages = ref([])
const inputText = ref('')
const sending = ref(false)
const chatBoxRef = ref(null)

// 流式输出时保持视口跟随到底部
function scrollToBottom() {
  nextTick(() => {
    const el = chatBoxRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

// 清空对话
function clearChat() {
  messages.value = []
  // 切换轨迹模式为 chat
  state.trajMode = 'chat'
  showToast('对话已清空', 'info')
}

// 发送消息（SSE 流式接收）
async function sendMessage() {
  const msg = inputText.value.trim()
  if (!msg || sending.value) return

  // 追加用户消息
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
  // 强制切换轨迹模式为 chat，确保轨迹面板渲染聊天链路
  state.trajMode = 'chat'

  // 创建占位的 assistant 消息，流式追加内容
  messages.value.push({
    role: 'assistant',
    text: '',
    meta: { routerType: '', routerCostMs: null, totalCostMs: null },
    references: [],
    streaming: true,
  })
  // 取出刚插入的 assistant 消息代理，后续直接 mutate 触发响应式更新
  const cur = messages.value[messages.value.length - 1]
  scrollToBottom()

  try {
    const res = await fetch(API_BASE + '/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
      body: JSON.stringify({ message: msg }),
    })
    if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}`)

    // 读取 SSE 流：ReadableStream reader + TextDecoder
    const reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''

    while (true) {
      const { value, done: streamDone } = await reader.read()
      if (streamDone) break
      buffer += decoder.decode(value, { stream: true })

      // SSE 数据按 \n\n 分隔，每段含 "data: {...}"
      let idx
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const chunk = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        // 解析 "data: ..." 行
        const lines = chunk.split('\n')
        for (const line of lines) {
          if (!line.startsWith('data:')) continue
          const jsonStr = line.slice(5).trim()
          if (!jsonStr) continue
          const ev = tryJson(jsonStr)
          if (!ev) continue

          if (ev.type === 'META') {
            // 更新 meta：routerType / routerCostMs
            cur.meta.routerType = ev.routerType || ''
            if (ev.routerCostMs != null) cur.meta.routerCostMs = ev.routerCostMs
            // 渲染引用
            if (ev.references) cur.references = ev.references
            // 通知父组件转发到轨迹面板
            emit('chat-traj', ev)
          } else if (ev.type === 'TOKEN') {
            // 追加文本到当前 assistant 消息
            cur.text += (ev.data || '')
          } else if (ev.type === 'DONE') {
            // 更新总耗时，结束流式
            if (ev.totalCostMs != null) cur.meta.totalCostMs = ev.totalCostMs
            cur.streaming = false
          } else if (ev.type === 'ERROR') {
            // 追加错误文本
            cur.text += '\n[错误] ' + (ev.data || '未知错误')
            setConn('error', 'ERROR')
          }
          scrollToBottom()
        }
      }
    }

    // 流结束，确保标记为非流式（移除光标）
    cur.streaming = false
    setConn('idle', 'API IDLE')
  } catch (err) {
    // 调用失败：替换为错误提示
    cur.text = '调用失败: ' + err.message
    cur.streaming = false
    setConn('error', 'ERROR')
  } finally {
    sending.value = false
  }
}
</script>
