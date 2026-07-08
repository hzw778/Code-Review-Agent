package com.codereview.agent.agent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 运行状态（一次审查的全局上下文）
 *
 * <p>贯穿整个 ReAct 循环，记录：
 * <ul>
 *   <li>任务基本信息（taskId、类型、状态）</li>
 *   <li>输入参数（仓库地址、commitId）</li>
 *   <li>执行历史（所有 AgentStep）</li>
 *   <li>最终结果</li>
 * </ul>
 */
@Data
public class AgentState {

    /** 任务唯一 ID */
    private String taskId;

    /** 任务类型 */
    private TaskType taskType;

    /** 当前状态 */
    private AgentStatus status;

    /** 仓库地址（REVIEW 类型用） */
    private String repoUrl;

    /** commit ID（REVIEW 类型用） */
    private String commitId;

    /** ReAct 循环的步骤历史 */
    private List<AgentStep> steps = new ArrayList<>();

    /** 当前轮次（从 0 开始，0 表示还没开始） */
    private int currentRound = 0;

    /** 最终审查结果（SUCCESS 后才有） */
    private String finalResult;

    /** 失败原因（FAILED 后才有） */
    private String errorMessage;

    /** 创建时间戳 */
    private long createdAt;

    /** 更新时间戳 */
    private long updatedAt;
}
