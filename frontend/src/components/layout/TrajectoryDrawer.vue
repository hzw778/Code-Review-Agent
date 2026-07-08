<template>
  <!-- 遮罩（移动端） -->
  <div class="drawer-mask" :data-show="state.sideOpen ? 'true' : 'false'" @click="state.sideOpen = false"></div>

  <aside class="app-drawer" :data-open="state.sideOpen ? 'true' : 'false'">
    <!-- 抽屉头 -->
    <header class="app-drawer__head">
      <div>
        <h3 class="app-drawer__title">数据传输轨迹</h3>
        <p class="app-drawer__sub">{{ subtitle }}</p>
      </div>
      <button class="btn btn--ghost btn--icon btn--sm" @click="state.sideOpen = false" title="关闭">
        <BaseIcon name="x" :size="16" />
      </button>
    </header>

    <!-- 工具栏 -->
    <div class="traj-toolbar">
      <span class="pill pill--blue">{{ steps.length }} 步</span>
      <span class="pill">{{ statusLabel }}</span>
      <div style="flex:1;"></div>
      <button class="btn btn--ghost btn--sm" title="手动刷新" @click="refresh">
        <BaseIcon name="refresh-cw" :size="13" />
      </button>
    </div>

    <!-- 轨迹列表 -->
    <div class="app-drawer__body">
      <div class="traj-list" ref="trajListRef">
        <div v-if="steps.length === 0" class="traj-empty">
          <span class="traj-empty__icon">
            <BaseIcon name="activity" :size="22" />
          </span>
          <p class="traj-empty__title">暂无执行轨迹</p>
          <span class="traj-empty__desc">发起审查或发送聊天消息后，每一步数据传输会按时间顺序展开</span>
        </div>

        <template v-for="(item, i) in steps" :key="i">
          <!-- 多轮对话分隔线 -->
          <div v-if="item.sep" class="traj-sep">{{ item.sep }}</div>

          <!-- 步骤卡片 -->
          <div v-else class="step" :data-expanded="item.expanded ? 'true' : 'false'">
            <div class="step__head" @click="item.expanded = !item.expanded">
              <span class="step__round">{{ item.round ?? (i + 1) }}</span>
              <span class="step__action" :data-tool="item.action" :data-stage="item.stage">
                {{ item.action || item.stage }}
              </span>
              <span v-if="item.toolDesc" class="step__tooldesc">{{ item.toolDesc }}</span>
              <span class="step__cost">{{ item.costMs ?? 0 }} ms</span>
              <span class="step__chev">
                <BaseIcon name="chevron-right" :size="14" />
              </span>
            </div>

            <div class="step__body">
              <!-- review 模式 -->
              <template v-if="item.mode === 'review'">
                <div v-if="item.promptSentToLlm" class="block block--input">
                  <div class="block__head"><span class="block__dot"></span>发给 LLM 的完整 Prompt</div>
                  <div class="block__body">{{ item.promptSentToLlm }}</div>
                </div>
                <div v-if="item.llmRawResponse" class="block block--obs">
                  <div class="block__head"><span class="block__dot"></span>LLM 原始返回 (Raw Response)</div>
                  <div class="block__body">{{ item.llmRawResponse }}</div>
                </div>
                <div v-if="item.thought" class="block block--thought">
                  <div class="block__head"><span class="block__dot"></span>LLM 思考 (Thought)</div>
                  <div class="block__body">{{ item.thought }}</div>
                </div>
                <div class="block block--action">
                  <div class="block__head"><span class="block__dot"></span>工具参数 (ActionInput)</div>
                  <div class="block__body kv-table">
                    <template v-if="item.actionInputObj">
                      <div v-for="(v, k) in item.actionInputObj" :key="k" class="kv">
                        <span class="kv__k">{{ k }}</span>
                        <span class="kv__v">{{ formatVal(v) }}</span>
                      </div>
                    </template>
                    <div v-else class="kv"><span class="kv__v">{{ item.actionInput || '(空)' }}</span></div>
                  </div>
                </div>
                <div class="block block--obs">
                  <div class="block__head"><span class="block__dot"></span>工具返回 (Observation)</div>
                  <div class="block__body" :class="{ 'is-json': item.obsIsJson }">{{ item.obsDisplay }}</div>
                </div>
              </template>

              <!-- chat 模式 -->
              <template v-else>
                <div v-if="item.basicInfo" class="block block--input">
                  <div class="block__head"><span class="block__dot"></span>基本信息</div>
                  <div class="block__body kv-table">
                    <div v-for="(v, k) in item.basicInfo" :key="k" v-show="v != null && v !== ''" class="kv">
                      <span class="kv__k">{{ k }}</span>
                      <span class="kv__v">{{ v }}</span>
                    </div>
                  </div>
                </div>
                <div v-if="item.userInput" class="block block--input">
                  <div class="block__head"><span class="block__dot"></span>用户输入 (userInput)</div>
                  <div class="block__body">{{ item.userInput }}</div>
                </div>
                <div v-if="item.promptTemplate" class="block block--input">
                  <div class="block__head"><span class="block__dot"></span>Prompt 模板 (promptTemplate)</div>
                  <div class="block__body">{{ item.promptTemplate }}</div>
                </div>
                <div v-if="item.finalPrompt" class="block block--input">
                  <div class="block__head"><span class="block__dot"></span>最终 Prompt (finalPrompt)</div>
                  <div class="block__body">{{ item.finalPrompt }}</div>
                </div>
                <div v-if="item.systemPrompt" class="block block--input">
                  <div class="block__head"><span class="block__dot"></span>System Prompt</div>
                  <div class="block__body">{{ item.systemPrompt }}</div>
                </div>
                <div v-if="item.userPrompt" class="block block--input">
                  <div class="block__head"><span class="block__dot"></span>User Prompt</div>
                  <div class="block__body">{{ item.userPrompt }}</div>
                </div>
                <div v-if="item.query" class="block block--input">
                  <div class="block__head"><span class="block__dot"></span>检索 Query</div>
                  <div class="block__body">{{ item.query }}</div>
                </div>
                <div v-if="item.searchParams" class="block block--input">
                  <div class="block__head"><span class="block__dot"></span>检索参数</div>
                  <div class="block__body kv-table">
                    <div v-for="(v, k) in item.searchParams" :key="k" v-show="v != null" class="kv">
                      <span class="kv__k">{{ k }}</span>
                      <span class="kv__v">{{ v }}</span>
                    </div>
                  </div>
                </div>
                <div v-if="item.rawResponse" class="block block--obs">
                  <div class="block__head"><span class="block__dot"></span>LLM 原始响应 (rawResponse)</div>
                  <div class="block__body">{{ item.rawResponse }}</div>
                </div>
                <div v-if="item.parsedType" class="block block--obs">
                  <div class="block__head"><span class="block__dot"></span>解析结果 (parsedType)</div>
                  <div class="block__body">{{ item.parsedType }}</div>
                </div>
                <div v-if="item.rulesJson" class="block block--obs">
                  <div class="block__head"><span class="block__dot"></span>规则检索结果 (rules JSON)</div>
                  <div class="block__body is-json">{{ item.rulesJson }}</div>
                </div>
                <div v-if="item.codesJson" class="block block--obs">
                  <div class="block__head"><span class="block__dot"></span>代码检索结果 (codes JSON)</div>
                  <div class="block__body is-json">{{ item.codesJson }}</div>
                </div>
              </template>
            </div>

            <!-- observation 摘要 -->
            <div v-if="item.summary" class="step__summary" v-html="item.summary"></div>
          </div>
        </template>
      </div>
    </div>

    <!-- 抽屉底 -->
    <footer class="app-drawer__foot">
      <span class="kv">
        <span class="kv__k">API</span>
        <span class="kv__v">/api → localhost:8080</span>
      </span>
    </footer>
  </aside>
</template>

<script setup>
import { ref, reactive, nextTick } from 'vue'
import { state, showToast, api, tryJson, fmtJson, escapeHtml } from '../../composables/useApi.js'
import BaseIcon from '../BaseIcon.vue'

const trajListRef = ref(null)

const steps = ref([])
const subtitle = ref('每一轮 LLM 思考 · 工具调用 · 返回数据')
const statusLabel = ref('未启动')

const TOOL_DESCRIPTIONS = {
  GitDiffTool: '拉取 commit 代码变更',
  AstAnalysisTool: 'AST 静态分析',
  RagSearchTool: 'RAG 规范检索',
  FileReadTool: '读取文件上下文',
  RuleMatchTool: '批量规则匹配',
  SimilarCodeTool: '相似代码检索',
  Finish: '结束循环，输出结论',
}
const STAGE_DESCRIPTIONS = {
  Router: 'LLM 分类（qwen-flash）',
  RagSearchTool: '向量检索（embedding + ES KNN）',
  LLM: '流式生成回复（glm-4.5-air）',
}

// ============== Review 模式 ==============
let lastTrajLen = 0

function renderReviewTraj(data) {
  if (state.trajMode !== 'review') return
  subtitle.value = 'ReAct 循环 · thought / action / observation'
  const rawSteps = data.steps || []
  statusLabel.value = data.status || '—'

  if (rawSteps.length === 0) {
    steps.value = []
    lastTrajLen = 0
    return
  }

  if (rawSteps.length > lastTrajLen) {
    for (let i = lastTrajLen; i < rawSteps.length; i++) {
      steps.value.push(buildReviewStep(rawSteps[i], i === rawSteps.length - 1))
    }
    lastTrajLen = rawSteps.length
  } else if (rawSteps.length < lastTrajLen) {
    steps.value = rawSteps.map(s => buildReviewStep(s, false))
    lastTrajLen = rawSteps.length
  }
  scrollToBottom()
}

function buildReviewStep(step, autoExpand) {
  const action = step.action || '—'
  const obsObj = tryJson(step.observation)
  return reactive({
    mode: 'review',
    round: step.round,
    action,
    toolDesc: TOOL_DESCRIPTIONS[action] || '',
    costMs: step.costMs ?? 0,
    expanded: autoExpand,
    thought: step.thought,
    actionInput: step.actionInput,
    actionInputObj: tryJson(step.actionInput),
    promptSentToLlm: step.promptSentToLlm,
    llmRawResponse: step.llmRawResponse,
    obsDisplay: obsObj ? fmtJson(obsObj) : (step.observation || '(空)'),
    obsIsJson: !!obsObj,
    summary: summarizeObservation(action, step.observation),
  })
}

function summarizeObservation(action, observation) {
  if (!observation) return ''
  const obj = tryJson(observation)
  if (!obj) return ''
  if (action === 'GitDiffTool') {
    if (obj.error) return `<span class="sum-tag sum-tag--err">失败</span> ${escapeHtml(obj.error)}`
    return `<span class="sum-tag sum-tag--ok">变更 ${obj.totalFiles || 0} 文件</span> +${obj.totalAdditions || 0} -${obj.totalDeletions || 0}`
  }
  if (action === 'AstAnalysisTool') {
    if (obj.error) return `<span class="sum-tag sum-tag--err">失败</span> ${escapeHtml(obj.error)}`
    const issues = obj.issues || obj.results || []
    return `<span class="sum-tag sum-tag--ok">检测到 ${Array.isArray(issues) ? issues.length : 0} 个问题</span>`
  }
  if (action === 'RagSearchTool' || action === 'RuleMatchTool' || action === 'SimilarCodeTool') {
    if (obj.error) return `<span class="sum-tag sum-tag--err">失败</span> ${escapeHtml(obj.error)}`
    const rules = obj.rules || []
    const codes = obj.codes || []
    const parts = []
    if (rules.length) parts.push(`<span class="sum-tag sum-tag--ok">规则 ${rules.length} 条</span>`)
    if (codes.length) parts.push(`<span class="sum-tag sum-tag--ok">代码 ${codes.length} 段</span>`)
    return parts.join(' ')
  }
  return ''
}

// ============== Chat 模式 ==============
function renderChatTraj(ev) {
  if (state.trajMode !== 'chat') return
  subtitle.value = '数据传输链路 · Router → RAG → LLM'
  const trace = ev.trace || []
  statusLabel.value = ev.routerType || '—'

  if (steps.value.length > 0 && trace.length > 0 && trace[0].stage === 'Router') {
    steps.value.push(reactive({ sep: `── 对话 ${Math.floor(steps.value.length / 3) + 1} ──` }))
  }

  trace.forEach((t, i) => {
    const stage = t.stage || '—'
    const basicInfo = {
      stage: t.stage,
      detail: t.detail,
      costMs: t.costMs,
      model: t.model,
      status: t.status,
    }
    const searchParams = (t.topK != null || t.similarityThreshold != null) ? {
      topK: t.topK,
      similarityThreshold: t.similarityThreshold,
      ruleCount: t.ruleCount,
      codeCount: t.codeCount,
    } : null

    steps.value.push(reactive({
      mode: 'chat',
      round: i + 1,
      stage,
      action: stage,
      toolDesc: STAGE_DESCRIPTIONS[stage] || '',
      costMs: t.costMs ?? 0,
      expanded: i === trace.length - 1,
      basicInfo,
      userInput: t.userInput,
      promptTemplate: t.promptTemplate,
      finalPrompt: t.finalPrompt,
      systemPrompt: t.systemPrompt,
      userPrompt: t.userPrompt,
      query: t.query,
      searchParams,
      rawResponse: t.rawResponse,
      parsedType: t.parsedType,
      rulesJson: t.rules ? JSON.stringify(t.rules, null, 2) : null,
      codesJson: t.codes ? JSON.stringify(t.codes, null, 2) : null,
    }))
  })
  scrollToBottom()
}

// ============== 重置 ==============
function resetTrajectory(mode) {
  state.trajMode = mode
  steps.value = []
  lastTrajLen = 0
  statusLabel.value = '未启动'
  subtitle.value = mode === 'review'
    ? 'ReAct 循环 · thought / action / observation'
    : '数据传输链路 · Router → RAG → LLM'
}

// ============== 手动刷新 ==============
async function refresh() {
  if (state.trajMode === 'review') {
    if (!state.currentTaskId) { showToast('当前没有审查任务', 'error'); return }
    try {
      const data = await api(`/review/${state.currentTaskId}/steps`)
      renderReviewTraj(data)
    } catch (e) { showToast('刷新失败: ' + e.message, 'error') }
  } else {
    showToast('聊天轨迹请在发送消息时查看', 'info')
  }
}

function formatVal(v) {
  const s = typeof v === 'string' ? v : JSON.stringify(v)
  return s.length > 80 ? s.slice(0, 80) + '…' : s
}

function scrollToBottom() {
  nextTick(() => {
    if (trajListRef.value) trajListRef.value.scrollTop = trajListRef.value.scrollHeight
  })
}

defineExpose({ renderReviewTraj, renderChatTraj, resetTrajectory })
</script>
