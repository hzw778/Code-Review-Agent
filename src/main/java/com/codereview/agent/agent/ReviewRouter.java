package com.codereview.agent.agent;


import com.codereview.agent.agent.model.RouterResult;
import com.codereview.agent.agent.model.TaskType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 任务路由器：用轻量模型对用户输入分类，决定走哪条处理链路。
 *
 * <p>使用独立的 qwen-flash 轻量模型（非流式），与最终回复的 glm-4.5-air 隔离。
 *
 * <p>三类任务：
 * <ul>
 *   <li>CHITCHAT：闲聊，直接 LLM 回答</li>
 *   <li>CODE_QA：代码问答，走 RAG + LLM</li>
 *   <li>REVIEW：代码审查，走完整 ReAct 循环</li>
 * </ul>
 *
 * <p>设计原则：简单任务简单处理，复杂任务才启动 Agent（资源分级）。
 */
@Slf4j
@Service
public class ReviewRouter {

    private final ChatClient routerChatClient;

    public ReviewRouter(@Qualifier("routerChatClient") ChatClient routerChatClient) {
        this.routerChatClient = routerChatClient;
    }

    /** 分类 prompt 模板（公开供 ChatService 在 trace 里展示） */
    public static final String ROUTER_PROMPT_TEMPLATE = """
            你是一个任务分类器。请对用户输入进行分类，只返回以下三个词之一，不要返回任何其他内容：

            - CHITCHAT：闲聊、问候、与代码无关的对话（如"你好"、"你是谁"）
            - CODE_QA：代码知识问答、概念解释、技术问题（如"什么是 try-with-resources"、"怎么处理空指针"）
            - REVIEW：代码审查请求、检查代码、分析 commit（如"帮我审查这个提交"、"检查这段代码有什么问题"）

            用户输入：%s

            分类结果（只返回一个词）：
            """;

    /**
     * 对用户输入分类。
     *
     * @param userInput 用户输入
     * @return 分类结果
     */
    public RouterResult route(String userInput) {
        log.info("[Router] 开始分类, 输入预览={}", preview(userInput));
        long start = System.currentTimeMillis();

        String prompt = String.format(ROUTER_PROMPT_TEMPLATE, userInput);
        String rawResponse = routerChatClient.prompt()
                .user(prompt)
                .call()
                .content();

        TaskType taskType = parseTaskType(rawResponse);
        long costMs = System.currentTimeMillis() - start;

        log.info("[Router] 分类完成, 类型={}, 耗时={}ms, 原始回复={}",
                taskType, costMs, rawResponse);

        return RouterResult.builder()
                .taskType(taskType)
                .rawResponse(rawResponse)
                .costMs(costMs)
                .build();
    }

    /**
     * 解析 LLM 返回的文本为 TaskType，带容错。
     */
    private TaskType parseTaskType(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            log.warn("[Router] LLM 返回空，默认 CHITCHAT");
            return TaskType.CHITCHAT;
        }
        String normalized = rawResponse.trim().toUpperCase();
        // 处理 LLM 可能带的额外文字，提取第一个匹配的枚举值
        for (TaskType type : TaskType.values()) {
            if (normalized.contains(type.name())) {
                return type;
            }
        }
        log.warn("[Router] 无法解析分类[{}]，默认 CHITCHAT", rawResponse);
        return TaskType.CHITCHAT;
    }

    /**
     * 输入预览（取前 50 字符，避免日志过长）。
     */
    private String preview(String input) {
        if (input == null) return "null";
        return input.length() > 50 ? input.substring(0, 50) + "..." : input;
    }
}
