package com.codereview.agent.agent;

import com.codereview.agent.agent.model.RouterResult;
import com.codereview.agent.agent.model.TaskType;
import com.codereview.agent.rag.model.CodeSearchResult;
import com.codereview.agent.rag.model.RuleSearchResult;
import com.codereview.agent.tool.RagSearchTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
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
    private final RagSearchTool ragSearchTool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatService(ReviewRouter reviewRouter,
                       @Qualifier("chatClient") ChatClient chatClient,
                       RagSearchTool ragSearchTool) {
        this.reviewRouter = reviewRouter;
        this.chatClient = chatClient;
        this.ragSearchTool = ragSearchTool;
    }

    /** CODE_QA 系统提示 */
    private static final String QA_SYSTEM = """
            你是 Java 代码规范助手。请基于下方检索到的【规范条目】和【代码示例】回答用户问题。
            如果检索结果为空，请基于你自己的知识回答，并标注"未检索到相关规范"。
            回答要简洁、可操作，必要时引用规范条目名称。

            【规范条目】
            %s

            【代码示例】
            %s
            """;

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
     *   <li>META：routerType / references / trace（前端先渲染元数据）</li>
     *   <li>TOKEN * N：LLM 流式输出的 token（前端拼接显示打字机效果）</li>
     *   <li>DONE：totalCostMs 等汇总信息</li>
     * </ol>
     */
    public Flux<ChatStreamEvent> streamChat(String userInput) {
        long start = System.currentTimeMillis();
        log.info("[ChatService] 收到流式消息, 长度={}", userInput.length());

        List<Map<String, Object>> trace = new ArrayList<>();
        List<Map<String, Object>> references = new ArrayList<>();

        // 1. Router 分类（同步，qwen-flash）
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
        meta.put("routerType", taskType.name());
        meta.put("routerRaw", routerResult.getRawResponse());
        meta.put("routerCostMs", routerResult.getCostMs());

        // 2. 根据分类准备 systemPrompt 和 references
        String systemPrompt;
        switch (taskType) {
            case CHITCHAT -> {
                systemPrompt = CHITCHAT_SYSTEM;
            }
            case CODE_QA -> {
                long t1 = System.currentTimeMillis();
                RagSearchTool.SearchResult sr;
                try {
                    sr = ragSearchTool.searchAll(userInput);
                } catch (Exception e) {
                    log.error("[ChatService] RAG 检索失败", e);
                    return Flux.just(ChatStreamEvent.meta(meta), ChatStreamEvent.error("RAG 检索失败: " + e.getMessage()));
                }
                long ragCost = System.currentTimeMillis() - t1;

                // RAG trace：含完整的检索 query 和检索结果 JSON
                Map<String, Object> ragTrace = traceStep("RagSearchTool",
                        "规则=" + (sr.getRules() != null ? sr.getRules().size() : 0)
                                + " 代码=" + (sr.getCodes() != null ? sr.getCodes().size() : 0),
                        ragCost);
                ragTrace.put("model", "text-embedding-v3 (ES KNN)");
                ragTrace.put("query", userInput);
                ragTrace.put("topK", 3);
                ragTrace.put("similarityThreshold", 0.5);
                ragTrace.put("ruleCount", sr.getRules() != null ? sr.getRules().size() : 0);
                ragTrace.put("codeCount", sr.getCodes() != null ? sr.getCodes().size() : 0);
                ragTrace.put("rules", sr.getRules());
                ragTrace.put("codes", sr.getCodes());
                trace.add(ragTrace);

                if (sr.getRules() != null) {
                    for (RuleSearchResult r : sr.getRules()) {
                        references.add(refMap("rule", r.getRuleName(), r.getSource(), r.getScore(), r.getContent()));
                    }
                }
                if (sr.getCodes() != null) {
                    for (CodeSearchResult c : sr.getCodes()) {
                        references.add(refMap("code", c.getClassName() + "#" + c.getMethodName(),
                                c.getSource(), c.getScore(),
                                c.getSignature() != null ? c.getSignature() : c.getContent()));
                    }
                }
                systemPrompt = String.format(QA_SYSTEM, formatRules(sr.getRules()), formatCodes(sr.getCodes()));
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

        // 3. LLM 流式调用（glm-4.5-air）
        final String finalSystemPrompt = systemPrompt;
        long llmStart = System.currentTimeMillis();

        // 把 LLM 步骤先加入 trace（token 完成后更新 costMs）
        Map<String, Object> llmTrace = traceStep("LLM", "ChatClient.stream() (glm-4.5-air)", 0);
        llmTrace.put("model", "glm-4.5-air (流式)");
        llmTrace.put("systemPrompt", finalSystemPrompt);
        llmTrace.put("userPrompt", userInput);
        llmTrace.put("status", "streaming");
        trace.add(llmTrace);

        Flux<String> tokenFlux = chatClient.prompt()
                .system(finalSystemPrompt)
                .user(userInput)
                .stream()
                .content();

        return Flux.concat(
                Flux.just(ChatStreamEvent.meta(meta)),
                tokenFlux.map(ChatStreamEvent::token)
                        .doOnComplete(() -> {
                            long llmCost = System.currentTimeMillis() - llmStart;
                            llmTrace.put("costMs", llmCost);
                            llmTrace.put("status", "done");
                            // 注意：meta 已经发出，无法再更新 trace；这里仅用于日志
                            log.info("[ChatService] LLM 流式完成, cost={}ms", llmCost);
                        })
                        .onErrorResume(e -> {
                            log.error("[ChatService] LLM 流式失败", e);
                            return Flux.just(ChatStreamEvent.error("LLM 调用失败: " + e.getMessage()));
                        }),
                Flux.defer(() -> {
                    long total = System.currentTimeMillis() - start;
                    Map<String, Object> done = new LinkedHashMap<>();
                    done.put("totalCostMs", total);
                    // 在 DONE 事件里返回最终的 trace（含 LLM 完整耗时）
                    Map<String, Object> finalTrace = new LinkedHashMap<>();
                    finalTrace.put("trace", trace);
                    done.putAll(finalTrace);
                    return Flux.just(ChatStreamEvent.done(done));
                })
        );
    }

    /** trace 单步 */
    private Map<String, Object> traceStep(String stage, String detail, long costMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", stage);
        m.put("detail", detail);
        m.put("costMs", costMs);
        return m;
    }

    /** 引用项 */
    private Map<String, Object> refMap(String kind, String title, String source, double score, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("title", title);
        m.put("source", source);
        m.put("score", score);
        m.put("content", content != null && content.length() > 200 ? content.substring(0, 200) + "..." : content);
        return m;
    }

    private String formatRules(List<RuleSearchResult> rules) {
        if (rules == null || rules.isEmpty()) return "(无)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rules.size(); i++) {
            RuleSearchResult r = rules.get(i);
            sb.append(i + 1).append(". [").append(r.getRuleName()).append("] (score=")
              .append(String.format("%.3f", r.getScore())).append(")\n")
              .append(r.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    private String formatCodes(List<CodeSearchResult> codes) {
        if (codes == null || codes.isEmpty()) return "(无)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < codes.size(); i++) {
            CodeSearchResult c = codes.get(i);
            sb.append(i + 1).append(". ").append(c.getClassName()).append("#").append(c.getMethodName())
              .append(" (score=").append(String.format("%.3f", c.getScore())).append(")\n")
              .append(c.getContent()).append("\n\n");
        }
        return sb.toString();
    }
}
