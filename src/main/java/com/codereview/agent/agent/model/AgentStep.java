package com.codereview.agent.agent.model;

import lombok.Builder;
import lombok.Data;

/**
 * ReAct 循环的单步记录
 *
 * <p>每轮循环产生一个 AgentStep，记录 Thought/Action/Observation。
 * 所有历史 AgentStep 会塞进下一轮的 prompt，作为 LLM 的短期记忆。
 */
@Data
@Builder
public class AgentStep {

    /** 轮次（从 1 开始） */
    private int round;

    /** LLM 的思考内容 */
    private String thought;

    /** 调用的工具名（如 GitDiffTool），Finish 表示完成 */
    private String action;

    /** 工具的输入参数（JSON 字符串） */
    private String actionInput;

    /** 工具的返回结果（JSON 字符串） */
    private String observation;

    /** 本轮耗时（毫秒） */
    private long costMs;

    /** 发给 LLM 的完整 prompt（含系统指令+历史步骤+当前任务） */
    private String promptSentToLlm;

    /** LLM 返回的原始内容（未解析的 JSON 字符串） */
    private String llmRawResponse;
}
