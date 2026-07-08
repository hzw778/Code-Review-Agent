package com.codereview.agent.agent;

import com.codereview.agent.agent.model.RouterResult;
import com.codereview.agent.agent.model.TaskType;
import com.codereview.agent.guardrail.GuardrailResult;
import com.codereview.agent.guardrail.OutputGuardrail;
import com.codereview.agent.guardrail.PromptInjectionDetector;
import com.codereview.agent.repository.entity.ChatMessage;
import com.codereview.agent.repository.entity.ChatSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天编排服务
 *
 * <p>串联 Router → 三分支处理：
 * <ul>
 *   <li>CHITCHAT：直接 LLM 流式回答</li>
 *   <li>CODE_QA：RAG 检索（规范+代码）→ 拼 prompt → LLM 流式回答</li>
 *   <li>REVIEW：返回引导提示</li>
 * </ul>
 *
 * <p>多轮记忆架构（Redis + MySQL 双层）：
 * <ul>
 *   <li>Redis（热）：ChatMemoryService 维护滑动窗口 10 轮，LLM 记忆读取 O(1)</li>
 *   <li>MySQL（冷）：ChatSessionService 全量持久化，历史回看用</li>
 * </ul>
 *
 * <p>模型分工：
 * <ul>
 *   <li>qwen-flash（非流式）：Router 分类（内部决策）</li>
 *   <li>glm-4.5-air（流式）：最终回复（面向用户，SSE 打字机效果）</li>
 * </ul>
 *
 * <p>流式接口：streamChat 返回 Flux<ChatStreamEvent>，前端按事件类型渲染。
 */
@Slf4j
@Service
public class ChatService {

    private final ReviewRouter reviewRouter;
    private final ChatClient chatClient;
    private final CodeQaService codeQaService;
    private final PromptInjectionDetector promptInjectionDetector;
    private final OutputGuardrail outputGuardrail;
    private final ChatSessionService chatSessionService;
    private final ChatMemoryService chatMemoryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatService(ReviewRouter reviewRouter,
                       @Qualifier("chatClient") ChatClient chatClient,
                       CodeQaService codeQaService,
                       PromptInjectionDetector promptInjectionDetector,
                       OutputGuardrail outputGuardrail,
                       ChatSessionService chatSessionService,
                       ChatMemoryService chatMemoryService) {
        this.reviewRouter = reviewRouter;
        this.chatClient = chatClient;
        this.codeQaService = codeQaService;
        this.promptInjectionDetector = promptInjectionDetector;
        this.outputGuardrail = outputGuardrail;
        this.chatSessionService = chatSessionService;
        this.chatMemoryService = chatMemoryService;
    }

    /** CHITCHAT 系统提示 */
    private static final String CHITCHAT_SYSTEM = "你是 Code Review Agent，一个友好的代码审查助手。请简洁回答。";

    /** 流式事件类型 */
    public enum EventType { META, TOKEN, DONE, ERROR }

    /** 流式事件 */
    public record ChatStreamEvent(EventType type, String data, Map<String, Object> meta) {
        public static ChatStreamEvent meta(Map<String, Object> m){ return new ChatStreamEvent(EventType.META, null, m); }
        public static ChatStreamEvent token(String t){ return new ChatStreamEvent(EventType.TOKEN, t, null); }
        public static ChatStreamEvent done(Map<String, Object> m){ return new ChatStreamEvent(EventType.DONE, null, m); }
        public static ChatStreamEvent error(String msg){ return new ChatStreamEvent(EventType.ERROR, msg, null); }
    }

    /**
     * 流式聊天：返回事件流。
     *
     * <p>事件序列：
     * <ol>
     *   <li>META：sessionId / routerType / references / trace（前端先渲染元数据）</li>
     *   <li>TOKEN * N：LLM 流式输出的 token（前端拼接显示打字机效果）</li>
     *   <li>DONE：totalCostMs 等汇总信息</li>
     * </ol>
     *
     * @param userInput 用户输入
     * @param sessionId 会话 ID（null 表示新建会话）
     */
    public Flux<ChatStreamEvent> streamChat(String userInput, String sessionId) {
        long start = System.currentTimeMillis();
        log.info("[ChatService] 收到流式消息, 长度={}, sessionId={}", userInput.length(), sessionId);

        // 0. 输入护栏：在送 Router/LLM 前检测 prompt 注入
        GuardrailResult inputGuard = promptInjectionDetector.detect(userInput);
        if (inputGuard.blocked()) {
            log.warn("[ChatService] 输入命中注入护栏, 拒绝处理: reason={}", inputGuard.reason());
            Map<String, Object> blockMeta = new LinkedHashMap<>();
            blockMeta.put("blocked", true);
            blockMeta.put("reason", inputGuard.reason());
            blockMeta.put("totalCostMs", System.currentTimeMillis() - start);
            return Flux.just(
                    ChatStreamEvent.meta(blockMeta),
                    ChatStreamEvent.error("输入被安全护栏拦截: " + inputGuard.reason())
            );
        }

        // 1. 会话管理：sessionId 为空则创建新会话，否则复用已有会话
        String effectiveSessionId;
        ChatSession session;
        if (sessionId == null || sessionId.isBlank()) {
            session = chatSessionService.createSession(userInput);
            effectiveSessionId = session.getSessionId();
            log.info("[ChatService] 创建新会话, sessionId={}, title={}", effectiveSessionId, session.getTitle());
        } else {
            session = chatSessionService.getSession(sessionId).orElse(null);
            if (session == null) {
                session = chatSessionService.createSession(userInput);
                effectiveSessionId = session.getSessionId();
                log.warn("[ChatService] sessionId={} 无效, 已新建会话 {}", sessionId, effectiveSessionId);
            } else {
                effectiveSessionId = sessionId;
            }
        }

        // 2. 保存用户消息（双写 Redis 滑动窗口 + MySQL 全量）
        chatMemoryService.saveUserMessage(effectiveSessionId, userInput);
        chatSessionService.incrementMessageCount(effectiveSessionId);

        // 3. 加载最近 10 轮历史消息（Redis 优先，未命中回填 MySQL），用于多轮记忆
        List<ChatMessage> history = chatMemoryService.getMessagesForLlm(effectiveSessionId);

        List<Map<String, Object>> trace = new ArrayList<>();
        List<Map<String, Object>> references = new ArrayList<>();

        // 4. Router 分类（同步，qwen-flash）
        RouterResult routerResult;
        try {
            routerResult = reviewRouter.route(userInput);
        } catch (Exception e) {
            log.error("[ChatService] Router 失败", e);
            return Flux.just(ChatStreamEvent.error("Router 分类失败: " + e.getMessage()));
        }
        TaskType taskType = routerResult.getTaskType();

        // Router trace：含完整的 prompt / response / 模型信息
        Map<String, Object> routerTrace = traceStep("Router", "分类=" + taskType + " (qwen-flash)", routerResult.getCostMs());
        routerTrace.put("model", "qwen-flash (非流式)");
        routerTrace.put("userInput", userInput);
        routerTrace.put("promptTemplate", ReviewRouter.ROUTER_PROMPT_TEMPLATE);
        routerTrace.put("finalPrompt", String.format(ReviewRouter.ROUTER_PROMPT_TEMPLATE, userInput));
        routerTrace.put("rawResponse", routerResult.getRawResponse());
        routerTrace.put("parsedType", taskType.name());
        trace.add(routerTrace);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sessionId", effectiveSessionId);
        meta.put("routerType", taskType.name());
        meta.put("routerRaw", routerResult.getRawResponse());
        meta.put("routerCostMs", routerResult.getCostMs());

        // 5. 根据分类准备 systemPrompt 和 references
        String systemPrompt;
        switch (taskType) {
            case CHITCHAT -> {
                systemPrompt = CHITCHAT_SYSTEM;
            }
            case CODE_QA -> {
                // 委托给 CodeQaService：RAG 检索 + references 构造 + systemPrompt 拼装
                CodeQaService.QaContext qa;
                try {
                    qa = codeQaService.handle(userInput);
                } catch (Exception e) {
                    log.error("[ChatService] RAG 检索失败", e);
                    return Flux.just(ChatStreamEvent.meta(meta), ChatStreamEvent.error("RAG 检索失败: " + e.getMessage()));
                }
                references.addAll(qa.references());
                trace.add(qa.trace());
                systemPrompt = qa.systemPrompt();
            }
            case REVIEW -> {
                String guide = """
                        检测到你想做代码审查。代码审查需要仓库地址和 commitId，请到"代码审查"页签：
                        1. 在左侧仓库列表选择一个仓库（或直接填写 repoUrl）
                        2. 选择某个 commit
                        3. 点击"启动 Agent"

                        审查完成后，结果会展示在主区，每一步 ReAct 推理过程可在右侧"数据传输轨迹"面板查看。
                        """;
                meta.put("references", references);
                meta.put("trace", trace);
                long total = System.currentTimeMillis() - start;
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("totalCostMs", total);
                // REVIEW 引导也双写落库
                chatMemoryService.saveAssistantMessage(effectiveSessionId, guide, taskType.name(), null, total);
                chatSessionService.incrementMessageCount(effectiveSessionId);
                return Flux.just(
                        ChatStreamEvent.meta(meta),
                        ChatStreamEvent.token(guide),
                        ChatStreamEvent.done(done)
                );
            }
            default -> systemPrompt = CHITCHAT_SYSTEM;
        }

        meta.put("references", references);
        meta.put("trace", trace);

        // 6. LLM 流式调用（glm-4.5-air），带多轮历史
        final String finalSystemPrompt = systemPrompt;
        long llmStart = System.currentTimeMillis();

        Map<String, Object> llmTrace = traceStep("LLM", "ChatClient.stream() (glm-4.5-air)", 0);
        llmTrace.put("model", "glm-4.5-air (流式)");
        llmTrace.put("systemPrompt", finalSystemPrompt);
        llmTrace.put("userPrompt", userInput);
        llmTrace.put("historyRounds", history.isEmpty() ? 0 : (history.size() - 1) / 2);
        llmTrace.put("memorySource", "Redis+MySQL");
        llmTrace.put("status", "streaming");
        trace.add(llmTrace);

        // 构建多轮 messages：历史消息 + 本轮 user（已含在 history 末尾）
        List<Message> messages = buildMessagesForLlm(history);

        // 累积完整 assistant 回复，流结束后落库
        StringBuilder assistantReply = new StringBuilder();

        Flux<String> tokenFlux = chatClient.prompt()
                .system(finalSystemPrompt)
                .messages(messages)
                .stream()
                .content();

        final String finalSessionId = effectiveSessionId;
        final TaskType finalTaskType = taskType;

        return Flux.concat(
                Flux.just(ChatStreamEvent.meta(meta)),
                // 输出护栏：每个 token 经脱敏后再发前端
                tokenFlux.map(t -> {
                    String sanitized = outputGuardrail.sanitize(t).sanitized();
                    assistantReply.append(sanitized);
                    return ChatStreamEvent.token(sanitized);
                })
                        .doOnComplete(() -> {
                            long llmCost = System.currentTimeMillis() - llmStart;
                            llmTrace.put("costMs", llmCost);
                            llmTrace.put("status", "done");
                            // 双写落库 assistant 回复（Redis 滑动窗口 + MySQL 全量）
                            try {
                                String refsJson = references.isEmpty() ? null : objectMapper.writeValueAsString(references);
                                chatMemoryService.saveAssistantMessage(
                                        finalSessionId,
                                        assistantReply.toString(),
                                        finalTaskType.name(),
                                        refsJson,
                                        llmCost
                                );
                                chatSessionService.incrementMessageCount(finalSessionId);
                            } catch (Exception e) {
                                log.error("[ChatService] 保存助手消息失败, sessionId={}", finalSessionId, e);
                            }
                            log.info("[ChatService] LLM 流式完成, cost={}ms, sessionId={}", llmCost, finalSessionId);
                        })
                        .onErrorResume(e -> {
                            log.error("[ChatService] LLM 流式失败", e);
                            return Flux.just(ChatStreamEvent.error("LLM 调用失败: " + e.getMessage()));
                        }),
                Flux.defer(() -> {
                    long total = System.currentTimeMillis() - start;
                    Map<String, Object> done = new LinkedHashMap<>();
                    done.put("totalCostMs", total);
                    done.put("trace", trace);
                    return Flux.just(ChatStreamEvent.done(done));
                })
        );
    }

    /**
     * 把历史消息转换为 Spring AI 的 Message 列表。
     * 历史列表已按时间正序，最后一条是本轮 user 消息。
     */
    private List<Message> buildMessagesForLlm(List<ChatMessage> history) {
        List<Message> messages = new ArrayList<>();
        // 跳过最后一条（本轮 user 消息），因为 Spring AI 会通过 .user() 单独传入
        // 这里保留历史多轮对话，让 LLM 看到上下文
        for (int i = 0; i < history.size() - 1; i++) {
            ChatMessage m = history.get(i);
            if ("user".equals(m.getRole())) {
                messages.add(new UserMessage(m.getContent()));
            } else if ("assistant".equals(m.getRole())) {
                messages.add(new AssistantMessage(m.getContent()));
            }
        }
        // 末尾加上本轮 user 消息
        if (!history.isEmpty()) {
            ChatMessage last = history.get(history.size() - 1);
            messages.add(new UserMessage(last.getContent()));
        }
        return messages;
    }

    /** trace 单步 */
    private Map<String, Object> traceStep(String stage, String detail, long costMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", stage);
        m.put("detail", detail);
        m.put("costMs", costMs);
        return m;
    }
}
