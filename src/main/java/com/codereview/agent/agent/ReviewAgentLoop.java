package com.codereview.agent.agent;


import com.codereview.agent.config.AgentProperties;
import com.codereview.agent.agent.model.AgentState;
import com.codereview.agent.agent.model.AgentStatus;
import com.codereview.agent.agent.model.AgentStep;
import com.codereview.agent.tool.ToolExecutor;
import com.codereview.agent.tool.ToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * ReAct 主循环（Agent 核心）
 *
 * <p>执行流程：
 * <ol>
 *   <li>构造 prompt（含工具描述 + 历史步骤 + 用户任务）</li>
 *   <li>调 LLM，LLM 返回 Thought + Action（选工具）+ ActionInput（参数）</li>
 *   <li>解析 LLM 返回，执行工具，得到 Observation</li>
 *   <li>把本轮 Thought/Action/Observation 记录到 AgentStep</li>
 *   <li>判断是否完成（LLM 返回 Finish 或超过最大循环次数）</li>
 *   <li>未完成回到步骤 1，完成则设置 finalResult 和 SUCCESS</li>
 * </ol>
 *
 * <p>模型选择：Agent 每轮的"思考+选工具"属于内部决策，使用 qwen-flash（非流式）。
 * 最终审查报告通过 Finish 的 actionInput 返回，不单独调 LLM 生成。
 */
@Slf4j
@Service
public class ReviewAgentLoop {

    private final ChatClient chatClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final AgentProperties agentProperties;
    private final ReviewTaskStore taskStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReviewAgentLoop(@Qualifier("routerChatClient") ChatClient chatClient,
                           ToolRegistry toolRegistry,
                           ToolExecutor toolExecutor,
                           AgentProperties agentProperties,
                           ReviewTaskStore taskStore) {
        this.chatClient = chatClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.agentProperties = agentProperties;
        this.taskStore = taskStore;
    }

    /** Finish 标识：LLM 返回此值表示任务完成 */
    private static final String ACTION_FINISH = "Finish";

    /** ReAct 系统 prompt */
    private static final String SYSTEM_PROMPT = """
            你是一个代码审查 Agent。你的任务是审查用户提交的代码，找出问题并给出修改建议。

            你可以使用以下工具：
            %s

            工作流程：
            1. 先用 GitDiffTool 获取用户提交的代码变更
            2. 对 diff 中的 Java 文件用 AstAnalysisTool 分析代码问题
            3. 对 AST 发现的问题用 RagSearchTool 检索相关规范
            4. 综合所有信息，给出最终审查意见

            每一轮你必须返回以下 JSON 格式（不要返回其他任何内容）：
            {
              "thought": "你的思考过程",
              "action": "工具名 或 Finish",
              "actionInput": {参数对象}
            }

            当你认为已经收集到足够信息时，action 设为 "Finish"，
            actionInput 设为 {"result": "最终审查意见"}。

            重要规则：
            - 每轮只调用一个工具
            - 参数必须是合法 JSON
            - 不要编造工具名，只能用上面列出的工具
            """;

    /**
     * 启动 Agent 审查任务。
     *
     * @param taskId   外部传入的 taskId（与 submitReview 生成的一致，供前端轮询）
     * @param repoUrl  仓库地址
     * @param commitId commit ID
     * @return AgentState（含完整执行轨迹）
     */
    public AgentState run(String taskId, String repoUrl, String commitId) {
        // 1. 初始化状态（用外部传入的 taskId，确保前端能轮询到）
        AgentState state = new AgentState();
        state.setTaskId(taskId);
        state.setStatus(AgentStatus.RUNNING);
        state.setRepoUrl(repoUrl);
        state.setCommitId(commitId);
        state.setCreatedAt(System.currentTimeMillis());
        state.setUpdatedAt(System.currentTimeMillis());

        // 立即保存 RUNNING 状态，前端能看到从 PENDING → RUNNING
        taskStore.save(state);

        log.info("[Agent] 任务启动, taskId={}, repoUrl={}, commitId={}",
                state.getTaskId(), repoUrl, commitId);

        int maxLoop = agentProperties.getAgent().getMaxLoop();

        try {
            // 2. ReAct 循环
            for (int round = 1; round <= maxLoop; round++) {
                state.setCurrentRound(round);
                log.info("[Agent] ===== 第 {} 轮（最大 {}）=====", round, maxLoop);

                // 2.1 构造 prompt
                String prompt = buildPrompt(state);

                // 2.2 调 LLM
                long llmStart = System.currentTimeMillis();
                String llmResponse = chatClient.prompt()
                        .user(prompt)
                        .call()
                        .content();
                long llmCost = System.currentTimeMillis() - llmStart;

                log.info("[Agent] LLM 返回（耗时 {}ms）: {}", llmCost, llmResponse);

                // 2.3 解析 LLM 返回（同时记录完整 prompt 和原始响应，供前端展示）
                AgentStep step = parseStep(llmResponse, round, llmCost);
                step.setPromptSentToLlm(prompt);
                step.setLlmRawResponse(llmResponse);
                state.getSteps().add(step);

                // 每轮保存到 taskStore，前端能实时看到步骤
                state.setUpdatedAt(System.currentTimeMillis());
                taskStore.save(state);

                // 2.4 判断是否完成
                if (ACTION_FINISH.equals(step.getAction())) {
                    state.setStatus(AgentStatus.SUCCESS);
                    state.setFinalResult(extractFinishResult(step.getActionInput()));
                    log.info("[Agent] 任务完成, taskId={}, 共 {} 轮", state.getTaskId(), round);
                    break;
                }

                // 2.5 执行工具
                Map<String, Object> args = parseArgs(step.getActionInput());
                String observation = toolExecutor.execute(step.getAction(), args);
                step.setObservation(observation);

                // 工具执行后也保存，前端能看到 observation
                state.setUpdatedAt(System.currentTimeMillis());
                taskStore.save(state);

                log.info("[Agent] 第 {} 轮完成, action={}, observation长度={}",
                        round, step.getAction(), observation != null ? observation.length() : 0);

                // 最后一轮还没 Finish，标记失败
                if (round == maxLoop) {
                    state.setStatus(AgentStatus.FAILED);
                    state.setErrorMessage("超过最大循环次数 " + maxLoop + "，任务未完成");
                    log.warn("[Agent] 超过最大循环次数, taskId={}", state.getTaskId());
                }
            }
        } catch (Exception e) {
            log.error("[Agent] 任务异常, taskId={}", state.getTaskId(), e);
            state.setStatus(AgentStatus.FAILED);
            state.setErrorMessage("任务异常: " + e.getMessage());
        } finally {
            state.setUpdatedAt(System.currentTimeMillis());
            taskStore.save(state);  // 最终状态保存
        }

        return state;
    }

    /**
     * 构造 ReAct prompt（系统指令 + 工具描述 + 历史步骤 + 当前任务）。
     */
    private String buildPrompt(AgentState state) {
        String toolDesc = toolRegistry.getToolDescriptions();
        String systemPart = String.format(SYSTEM_PROMPT, toolDesc);

        StringBuilder history = new StringBuilder();
        history.append("仓库地址：").append(state.getRepoUrl()).append("\n");
        history.append("Commit ID：").append(state.getCommitId()).append("\n\n");

        if (state.getSteps().isEmpty()) {
            history.append("（这是第一轮，还没有历史步骤）\n");
        } else {
            history.append("历史步骤：\n");
            for (AgentStep step : state.getSteps()) {
                history.append("第").append(step.getRound()).append("轮：\n");
                history.append("  Thought: ").append(step.getThought()).append("\n");
                history.append("  Action: ").append(step.getAction()).append("\n");
                history.append("  ActionInput: ").append(step.getActionInput()).append("\n");
                history.append("  Observation: ").append(step.getObservation()).append("\n\n");
            }
        }

        history.append("请决定下一步（返回 JSON）：");
        return systemPart + "\n\n" + history;
    }

    /**
     * 解析 LLM 返回的 JSON 为 AgentStep。
     */
    private AgentStep parseStep(String llmResponse, int round, long llmCost) {
        try {
            // LLM 可能返回带 ```json 包裹的内容，提取 JSON 部分
            String json = extractJson(llmResponse);
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});

            return AgentStep.builder()
                    .round(round)
                    .thought((String) map.get("thought"))
                    .action((String) map.get("action"))
                    .actionInput(objectMapper.writeValueAsString(map.get("actionInput")))
                    .costMs(llmCost)
                    .build();
        } catch (Exception e) {
            log.error("[Agent] 解析 LLM 返回失败: {}", llmResponse, e);
            // 解析失败，构造一个 Finish 步骤结束循环
            return AgentStep.builder()
                    .round(round)
                    .thought("LLM 返回解析失败: " + e.getMessage())
                    .action(ACTION_FINISH)
                    .actionInput("{\"result\": \"解析失败，任务终止\"}")
                    .costMs(llmCost)
                    .build();
        }
    }

    /**
     * 从 LLM 返回中提取 JSON（处理 ```json 包裹）。
     */
    private String extractJson(String response) {
        if (response == null) return "{}";
        String trimmed = response.trim();
        // 去掉 ```json ... ``` 包裹
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (start > 0 && end > start) {
                return trimmed.substring(start + 1, end).trim();
            }
        }
        return trimmed;
    }

    /**
     * 解析 actionInput JSON 字符串为 Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String actionInput) {
        try {
            return objectMapper.readValue(actionInput, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[Agent] 解析 actionInput 失败: {}", actionInput);
            return Map.of();
        }
    }

    /**
     * 从 Finish 的 actionInput 提取最终结果。
     */
    private String extractFinishResult(String actionInput) {
        try {
            Map<String, Object> map = objectMapper.readValue(actionInput, new TypeReference<>() {});
            Object result = map.get("result");
            return result != null ? result.toString() : "无结果";
        } catch (Exception e) {
            return actionInput;
        }
    }
}
