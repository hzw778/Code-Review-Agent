<template>
  <section>
    <div class="page-head">
      <div>
        <h2 class="page-head__title">智能问答</h2>
        <p class="page-head__desc">Router 自动分类 · RAG 检索 · 流式回复</p>
      </div>
      <div class="page-head__actions">
        <button class="btn btn--ghost btn--sm" @click="clearChat">
          <BaseIcon name="trash" :size="13" />
          <span>清空</span>
        </button>
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
import { ref, nextTick } from 'vue'
import { state, setConn, showToast, api, API_BASE, tryJson } from '../composables/useApi.js'
import BaseIcon from './BaseIcon.vue'

const emit = defineEmits(['chat-traj'])

const messages = ref([])
const inputText = ref('')
const sending = ref(false)
const chatBoxRef = ref(null)

function scrollToBottom() {
  nextTick(() => {
    const el = chatBoxRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function clearChat() {
  messages.value = []
  state.trajMode = 'chat'
  showToast('对话已清空', 'info')
}

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
      body: JSON.stringify({ message: msg }),
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
</script>

<style scoped>
.chat-ref__text{
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
</style>
