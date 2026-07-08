package com.codereview.agent.agent.model;

/**
 * Agent 执行状态
 *
 * <p>状态流转：PENDING → RUNNING → SUCCESS / FAILED
 */
public enum AgentStatus {

    /** 待开始：任务已创建，Agent 循环尚未启动 */
    PENDING,

    /** 运行中：Agent 正在执行 ReAct 循环 */
    RUNNING,

    /** 成功：审查完成，有结果 */
    SUCCESS,

    /** 失败：异常或超时 */
    FAILED
}
