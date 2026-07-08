package com.codereview.agent.agent;

import com.codereview.agent.agent.model.AgentState;
import com.codereview.agent.agent.model.AgentStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 审查意见自检（Reflection）
 *
 * <p>对 Agent ReAct 循环生成的审查意见做二次校验，过滤误报。
 *
 * <p>工作原理：
 * <ol>
 *   <li>收集 Agent 的 finalResult 和关键历史步骤（AST 结果、RAG 结果）</li>
 *   <li>构造自检 prompt，让 LLM 判断每条意见是否有依据</li>
 *   <li>返回过滤后的审查意见</li>
 * </ol>
 *
 * <p>设计原则：简单过滤模式，只做一次 LLM 调用，避免过度工程。
 */
@Slf4j
@Service
public class ReviewReflection {

    private final ChatClient chatClient;

    public ReviewReflection(@Qualifier("routerChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /** 自检系统 prompt */
    private static final String REFLECTION_PROMPT = """
            你是代码审查质量检查员。下面是另一个 AI 生成的审查意见，请你校验其合理性。

            【原始审查意见】
            %s

            【AST 检测到的问题（客观事实）】
            %s

            【检索到的规范（依据）】
            %s

            校验规则：
            1. 每条意见必须有 AST 检测结果或规范依据支持
            2. 如果意见提到的问题在 AST 结果里不存在，标记为"误报"并删除
            3. 如果意见引用的规范在检索结果里不存在，标记为"幻觉"并删除
            4. 保留有依据的意见，可以合并重复项

            请输出过滤后的审查意见（直接输出意见内容，不要解释过滤过程）：
            """;

    /**
     * 对 Agent 的审查意见做自检。
     *
     * @param state Agent 运行后的状态（含 finalResult 和历史步骤）
     * @return 过滤后的审查意见
     */
    public String reflect(AgentState state) {
        log.info("[Reflection] 开始自检, taskId={}", state.getTaskId());
        long start = System.currentTimeMillis();

        String finalResult = state.getFinalResult();
        if (finalResult == null || finalResult.isBlank()) {
            log.warn("[Reflection] 审查意见为空, 跳过自检");
            return "审查意见为空";
        }

        // 提取 AST 结果和 RAG 结果作为校验依据
        String astFacts = extractObservationByAction(state, "AstAnalysisTool");
        String ragFacts = extractObservationByAction(state, "RagSearchTool");

        // 构造 prompt
        String prompt = String.format(REFLECTION_PROMPT, finalResult, astFacts, ragFacts);

        // 调 LLM 自检
        String reflectedResult;
        try {
            reflectedResult = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("[Reflection] 自检失败, 返回原始意见", e);
            return finalResult;
        }

        long cost = System.currentTimeMillis() - start;
        log.info("[Reflection] 自检完成, 耗时={}ms, 原始长度={}, 过滤后长度={}",
                cost, finalResult.length(),
                reflectedResult != null ? reflectedResult.length() : 0);

        return reflectedResult != null ? reflectedResult : finalResult;
    }

    /**
     * 从历史步骤中提取指定工具的 Observation。
     *
     * @param state    Agent 状态
     * @param actionName 工具名（如 AstAnalysisTool）
     * @return 该工具最后一次调用的 Observation，没有则返回"无"
     */
    private String extractObservationByAction(AgentState state, String actionName) {
        if (state.getSteps() == null) {
            return "无";
        }
        // 倒序找最后一次该工具的调用
        for (int i = state.getSteps().size() - 1; i >= 0; i--) {
            AgentStep step = state.getSteps().get(i);
            if (actionName.equals(step.getAction()) && step.getObservation() != null) {
                return step.getObservation();
            }
        }
        return "无";
    }
}
