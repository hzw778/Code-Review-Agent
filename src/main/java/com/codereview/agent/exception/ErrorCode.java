package com.codereview.agent.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 错误码枚举
 * <p>
 * 命名规范：模块_具体错误
 * 码值规范：4xx 客户端错误，5xx 服务端错误
 * </p>
 *
 * @author CodeReviewAgent
 * @date 2026-06-29
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // ========== 通用错误 4xx ==========
    PARAM_INVALID(400, "参数校验失败"),
    RESOURCE_NOT_FOUND(404, "资源不存在"),
    RESOURCE_CONFLICT(409, "资源冲突"),

    // ========== 仓库模块 1xxx ==========
    REPO_NOT_FOUND(1001, "仓库不存在"),
    REPO_CLONE_FAILED(1002, "仓库克隆失败"),
    REPO_ALREADY_EXISTS(1003, "仓库已存在"),

    // ========== Git 模块 2xxx ==========
    GIT_OPERATION_FAILED(2001, "Git 操作失败"),
    GIT_DIFF_PARSE_FAILED(2002, "Diff 解析失败"),

    // ========== AST 模块 3xxx ==========
    AST_PARSE_FAILED(3001, "AST 解析失败"),

    // ========== Agent 模块 4xxx ==========
    AGENT_LOOP_EXCEEDED(4001, "Agent 循环次数超限"),
    AGENT_TASK_FAILED(4002, "Agent 任务执行失败"),
    LLM_CALL_FAILED(4003, "LLM 调用失败"),

    // ========== 系统错误 5xx ==========
    INTERNAL_ERROR(500, "系统内部错误");

    private final int code;
    private final String message;
}