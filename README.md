# Code Review Agent

基于 Spring AI 1.1.0 + Spring Boot 3.5.0 + Java 17 的智能代码审查 Agent，支持 Git 仓库提交级审查（ReAct 循环 + 多工具协同）和代码规范问答（RAG 检索增强 + 流式输出）。

## 核心特性

- **ReAct Agent 循环**：Thought → Action → Observation 多轮推理，LLM 自主选择工具（GitDiff / AstAnalysis / RagSearch / FileRead / RuleMatch / SimilarCode），prompt 重放历史步骤实现无状态推理
- **6 工具协同**：GitDiffTool 获取变更、AstAnalysisTool 静态分析、FileReadTool 读取上下文（路径穿越防护）、RagSearchTool 语义检索、RuleMatchTool 批量规则匹配、SimilarCodeTool 相似代码检索
- **工具执行四层防护**：存在性 → 启用 → 超时 → 重试，差异化策略（GitDiff 120s+1重试 vs FileRead 10s+0），异常隔离永不抛给 Agent 循环
- **RAG 双路检索**：规则向量（阿里巴巴 Java 规范分块）+ 代码向量（方法级分块），references 含 lineNumbers 支持源码行溯源
- **Guardrail 双护栏**：输入护栏 PromptInjectionDetector（9 条正则检测注入）+ 输出护栏 OutputGuardrail（10 类敏感信息脱敏）
- **两层存储架构**：内存 TaskStore（实时轨迹）+ DB（持久报告与轨迹），互为降级，重启不丢失
- **流式 SSE 协议**：META → TOKEN*N → DONE 事件序列，前端打字机效果
- **安全设计**：FileReadTool 路径穿越防护（Path.normalize + startsWith workdir）+ 500 行截断续读提示

## 技术栈

| 层 | 技术 |
|----|------|
| 语言 / 框架 | Java 17 + Spring Boot 3.5.0 + Spring AI 1.1.0（降级到 3.5.0 兼容 ES 8.10.4） |
| LLM | GLM-4.5-air（流式，面向用户）+ qwen-flash（非流式，Router 内部决策）+ text-embedding-v3（向量化） |
| 静态分析 | JavaParser 3.25.8（AST 解析） |
| Git | JGit 6.8.0（diff 获取） |
| 存储 | MySQL（报告/轨迹持久化）+ Redis（缓存）+ Elasticsearch（向量检索） |
| 数据访问 | JPA Repository（不用 MyBatis） |
| 构建 | Maven |
| 前端 | 原生 HTML/CSS/JS + SSE |

## 架构图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                            前端 (frontend/, :5193)                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌────────────┐ │
│  │  代码审查页   │  │  智能问答页   │  │  历史报告页   │  │ 数据传输轨迹│ │
│  │  仓库+commit  │  │  SSE 打字机   │  │  issue 分组   │  │ ReAct 步骤  │ │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └────────────┘ │
└─────────┼─────────────────┼─────────────────┼──────────────────────────┘
          │ POST /review    │ POST /chat/stream│ GET /review/history
          │ /start          │ (SSE)            │ /review/{id}/report
          ▼                 ▼                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          后端 Controller 层                              │
│  ReviewController        ChatController        (复用 ReviewController)   │
└─────────┬─────────────────┬─────────────────┬──────────────────────────┘
          │                 │                 │
          ▼                 ▼                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          业务编排层 (Service)                            │
│                                                                         │
│  ┌─ ReviewService ──────────┐  ┌─ ChatService (编排) ────────────────┐ │
│  │ submitReview (异步)      │  │ 0. 输入护栏 PromptInjectionDetector │ │
│  │ → ReviewAsyncRunner      │  │ 1. Router 分类 (qwen-flash)         │ │
│  │   → ReviewAgentLoop      │  │ 2. 分支:                             │ │
│  │     (ReAct 循环)         │  │    CHITCHAT → 直接 LLM              │ │
│  │   → ReviewReportService  │  │    CODE_QA   → CodeQaService        │ │
│  │   → TrajectoryService    │  │    REVIEW    → 引导提示             │ │
│  │     (finally 并行落库)   │  │ 3. LLM 流式 (glm-4.5-air)           │ │
│  └──────────────────────────┘  │ 4. 输出护栏 OutputGuardrail          │ │
│                                └─────────────────────────────────────┘ │
└─────────┬───────────────────────────┬──────────────────────────────────┘
          │                           │
          ▼                           ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          能力服务层 (Tool / RAG)                         │
│                                                                         │
│  ┌─ AgentTool (6 个) ──────┐  ┌─ CodeQaService ─────────────────────┐ │
│  │ GitDiffTool  (120s+1重试)│  │ RagSearchTool.searchAll(query)      │ │
│  │ AstAnalysisTool (30s+0)  │  │ → 规则检索 (RuleVectorService)      │ │
│  │ RagSearchTool  (30s+2)   │  │ → 代码检索 (CodeVectorService)      │ │
│  │ FileReadTool   (10s+0)   │  │ → 拼 systemPrompt + references      │ │
│  │ RuleMatchTool  (60s+2)   │  │   (含 lineNumbers 引用溯源)         │ │
│  │ SimilarCodeTool (30s+2)  │  └─────────────────────────────────────┘ │
│  │        ↑                  │                                          │
│  │ ToolExecutor (四层防护)   │                                          │
│  │ 存在→启用→超时→重试      │                                          │
│  └──────────────────────────┘                                          │
└─────────┬───────────────────────────┬──────────────────────────────────┘
          │                           │
          ▼                           ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          模型层 (LLM)                                    │
│  ┌─ glm-4.5-air (流式) ────┐  ┌─ qwen-flash (非流式) ──┐  ┌─ text-embedding-v3 ─┐
│  │ Agent ReAct 推理        │  │ Router 分类             │  │ 规则/代码 向量化    │
│  │ CODE_QA 最终回复        │  │ (内部决策,不面向用户)   │  │ (DashScope, batch=10)│
│  └─────────────────────────┘  └─────────────────────────┘  └─────────────────────┘
└─────────┬───────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          存储层                                          │
│  ┌─ MySQL ──────────────┐  ┌─ Redis ──────────┐  ┌─ Elasticsearch ────┐ │
│  │ GitRepo (仓库注册)   │  │ 任务状态缓存     │  │ 规则向量 (rule)    │ │
│  │ ReviewRecord (报告)  │  │ (TaskStore 内存) │  │ 代码向量 (code)    │ │
│  │ ReviewIssue (问题)   │  │                  │  │                    │ │
│  │ ReviewTrajectory     │  │                  │  │                    │ │
│  │   (执行轨迹)         │  │                  │  │                    │ │
│  └──────────────────────┘  └──────────────────┘  └─────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

## 项目结构

```
src/main/java/com/codereview/agent/
├── agent/                          # Agent 核心编排
│   ├── ReviewAgentLoop.java        #   ReAct 循环（Thought/Action/Observation）
│   ├── ReviewAsyncRunner.java      #   @Async 异步执行 + finally 落库
│   ├── ReviewController.java       #   HTTP 接口（审查/状态/结果/轨迹/历史/报告）
│   ├── ReviewRouter.java           #   路由分类（qwen-flash，CHITCHAT/CODE_QA/REVIEW）
│   ├── ReviewReflection.java       #   自检（审查结果二次反思）
│   ├── ReviewReportService.java    #   报告持久化 + issue 解析
│   ├── TrajectoryService.java      #   轨迹持久化（内存优先+DB降级）
│   ├── ChatService.java            #   聊天编排（Router→三分支→LLM流式→护栏）
│   ├── CodeQaService.java          #   CODE_QA 能力服务（RAG检索+prompt拼装）
│   ├── ChatController.java         #   SSE 流式接口
│   └── tool/                       #   Agent 工具（实现 AgentTool 接口）
│       ├── GitDiffTool.java        #     获取 git diff
│       ├── AstAnalysisTool.java    #     AST 静态分析（包装 AstAnalyzer）
│       ├── RagSearchTool.java      #     RAG 语义检索
│       ├── FileReadTool.java       #     文件读取（路径穿越防护+500行截断）
│       ├── RuleMatchTool.java      #     批量规则匹配（查询增强+topK=1）
│       └── SimilarCodeTool.java    #     相似代码检索（正确写法参考）
├── ast/                            # AST 静态分析
│   ├── AstAnalyzer.java            #   JavaParser AST 遍历
│   ├── AstRuleVisitor.java         #   规则检测 Visitor
│   └── model/                      #   RuleType / RuleResult
├── config/                         # 配置
│   └── AgentProperties.java        #   Agent 工作目录 + Router 模型配置
├── guardrail/                      # 安全护栏
│   ├── GuardrailResult.java        #   三态 record（pass/block/sanitized）
│   ├── PromptInjectionDetector.java#   输入护栏（9条注入检测规则）
│   └── OutputGuardrail.java        #   输出护栏（10类敏感信息脱敏）
├── rag/                            # RAG 检索增强
│   ├── RagSearchTool.java          #   双路检索（规则+代码）
│   ├── RuleVectorService.java      #   规则向量检索
│   ├── CodeVectorService.java      #   代码向量检索
│   └── model/                      #   CodeChunk / RuleChunk / *SearchResult
├── repository/                     # JPA Repository + Entity
│   ├── entity/
│   │   ├── GitRepo.java            #   仓库注册实体
│   │   ├── ReviewRecord.java       #   审查记录（含统计冗余字段）
│   │   ├── ReviewIssue.java        #   单条问题（含 severityOrder 避免枚举字典序坑）
│   │   └── ReviewTrajectory.java   #   执行轨迹（ReAct 每步）
│   └── *Repository.java            #   JPA Repository
├── tool/                           # 工具基础设施
│   ├── AgentTool.java              #   工具接口
│   ├── ToolRegistry.java           #   工具注册表（启动扫描）
│   ├── ToolExecutor.java           #   工具执行器（四层防护）
│   ├── ToolPolicy.java             #   工具策略 record（超时/重试）
│   └── ToolPolicyProvider.java     #   差异化策略配置
└── CodeReviewApplication.java      # 启动类

frontend/                           # 前端（原生 HTML/CSS/JS）
├── index.html                      #   主页（审查页 + 问答页 + 历史）
├── app.js                          #   交互逻辑（SSE 接收 + 轨迹渲染）
└── styles.css                      #   样式（含 issue 分组配色）
```

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.6.3+
- MySQL 8.0+
- Elasticsearch 8.x（含向量检索插件）
- Redis（可选，缓存用）

### 1. 配置

复制 `application.yml.example` 为 `application-local.yml`，填入你的 API Key 和数据库连接：

```yaml
spring:
  ai:
    openai:
      chat:
        base-url: https://your-llm-endpoint/compatible-mode/v1
        api-key: your-glm-api-key
        options:
          model: glm-4.5-air
      embedding:
        base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
        api-key: your-dashscope-api-key
        options:
          model: text-embedding-v3
  datasource:
    url: jdbc:mysql://localhost:3306/code_review?useSSL=false&serverTimezone=Asia/Shanghai
    username: root
    password: your-password

app:
  workdir: ./workdir
  router-model:
    base-url: https://your-router-endpoint/compatible-mode/v1
    api-key: your-router-api-key
    model: qwen-flash-2025-07-28
```

> 注：`application-local.yml` 已在 `.gitignore` 中，不会上传 GitHub。

### 2. 初始化知识库

把阿里巴巴 Java 开发手册（Markdown 格式）放入 `knowledge/rules/`，把示例代码放入 `knowledge/codes/`，然后调用知识库入库接口：

```
POST /knowledge/ingest
```

系统会自动分块、向量化、写入 Elasticsearch。

### 3. 启动后端

```bash
mvn spring-boot:run
```

后端默认运行在 `http://localhost:8080`。

### 4. 启动前端

```bash
cd frontend
npx http-server -p 5193 -c-1 --cors
```

前端运行在 `http://localhost:5193`，浏览器打开 `http://localhost:5193/index.html`。

## 使用方式

### 代码审查

1. 在"代码审查"页签，填入 Git 仓库地址和 commit ID
2. 点击"启动 Agent"
3. 右侧"数据传输轨迹"面板实时展示 ReAct 推理过程（每轮 Thought/Action/Observation）
4. 审查完成后，主区展示结构化 issue 列表（按 CRITICAL/MAJOR/MINOR/INFO 分组）

### 智能问答

1. 在"智能问答"页签，直接用自然语言提问（如"空 catch 块应该怎么处理"）
2. Router 自动分类（CHITCHAT/CODE_QA/REVIEW）
3. CODE_QA 类型走 RAG 检索 + LLM 流式回答，打字机效果展示
4. 回答下方显示引用的规范条目和代码示例（含行号溯源）

### 历史报告

1. 在"历史报告"页签查看所有审查记录
2. 点击某条记录查看完整报告（含 issue 分组和原始 Markdown）

## 接口文档

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/review/start` | 发起审查（异步，立即返回 taskId） |
| POST | `/review/by-repo` | 通过已注册仓库 ID 发起审查 |
| GET | `/review/{taskId}/status` | 查询状态（内存优先，DB 降级） |
| GET | `/review/{taskId}/result` | 获取结果（内存优先，DB 降级） |
| GET | `/review/{taskId}/steps` | 获取 ReAct 执行轨迹（内存优先，DB 降级） |
| GET | `/review/history` | 审查历史列表（DB 持久化） |
| GET | `/review/{taskId}/report` | 完整报告 + 结构化 issue 分组（DB 持久化） |
| POST | `/chat/stream` | 智能问答（SSE 流式） |
| POST | `/repo/register` | 注册 Git 仓库 |
| POST | `/knowledge/ingest` | 知识库入库 |

## 设计要点

### 两层存储互为降级

- **内存 TaskStore**（ConcurrentHashMap）：存实时轨迹（steps 含 thought/action/observation），重启丢失
- **DB**（MySQL）：存持久报告（ReviewRecord/ReviewIssue）和轨迹（ReviewTrajectory），重启可回溯
- **steps 接口**：内存优先（实时性）+ DB 降级（持久性），返回 `source: memory/db` 标识数据来源
- **并行落库**：报告和轨迹在 finally 块独立 try-catch，任一失败不影响另一个

### 工具执行四层防护

1. **存在性校验**：工具是否注册
2. **启用校验**：ToolPolicy.enabled，未启用直接拒绝（灰度/熔断用）
3. **超时控制**：CompletableFuture.get(timeoutMs)，超时不重试（持续慢，重试无效）
4. **重试**：对非超时异常按 maxRetries 重试（偶发错误，重试有意义）

差异化策略：GitDiff 120s+1重试（网络克隆慢）vs FileRead 10s+0（本地 IO，卡住即 bug）。

### Guardrail 双护栏

- **输入护栏**（PromptInjectionDetector）：9 条正则规则检测指令覆盖/角色劫持/凭证窃取等，命中即 block 不送 LLM
- **输出护栏**（OutputGuardrail）：10 类敏感信息脱敏（API Key/JWT/私钥/邮箱/手机号），脱敏算法保留前4后2便于识别类型
- **策略不对称**：输入 block（止损），输出 sanitized（兜底）

### ReAct 循环

每轮 LLM 调用前，把历史步骤拼成 prompt 重放：`Previous steps: Step1[Thought=..., Action=..., Observation=...]`。LLM 基于历史自主决定下一步。最大循环次数熔断（防止死循环），finally 块兜底落库（无论成功失败都持久化）。

## 技术亮点

- **ReAct Agent 循环**：Thought→Action→Observation 多轮推理，LLM 自主选择工具，prompt 重放历史步骤实现无状态推理
- **两层存储架构**：内存 TaskStore 存实时轨迹，DB 存持久报告与轨迹，互为降级
- **工具执行四层防护**：ToolPolicy 差异化超时/重试，CompletableFuture.get(timeout) 实现超时，超时不重试/异常才重试
- **RAG 双路检索 + 引用溯源**：规则向量 + 代码向量双路 KNN 检索，references 含 lineNumbers 支持源码行溯源
- **Guardrail 双护栏**：输入护栏 9 条注入检测规则 + 输出护栏 10 类敏感信息脱敏
- **流式 SSE 协议**：META→TOKEN*N→DONE 事件序列，Router 用 qwen-flash 省 token，最终回复用 glm-4.5-air 保 UX
- **安全设计**：FileReadTool 路径穿越防护 + 500 行截断续读提示

## License

MIT
