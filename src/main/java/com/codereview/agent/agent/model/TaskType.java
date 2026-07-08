package com.codereview.agent.agent.model;

/**
 * 任务类型枚举
 *
 * <p>Router 根据用户输入分类，决定走哪条处理链路。
 * 简单任务简单处理，复杂任务才启动完整 Agent 循环（资源分级）。
 */
public enum TaskType {

    /** 闲聊：如"你好"、"你是谁"，直接 LLM 回答 */
    CHITCHAT,

    /** 代码问答：如"什么是 try-with-resources"，走 RAG + LLM */
    CODE_QA,

    /** 代码审查：如"审查这个 commit"，走完整 ReAct 循环 */
    REVIEW
}
