package com.codereview.agent.ast.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AST 检测结果（单条问题）
 * 表示 AST 分析发现的一个具体问题，包含定位信息（文件+行号）、
 * 问题分类（ruleType + severity）、问题描述和修改建议。
 * 后续会被序列化为 JSON 传给 LLM 做"语义级审查"。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleResult {

    /** 文件路径（相对路径或绝对路径，调用方约定） */
    private String file;

    /** 问题所在行号（1-based，从 AST 节点的 getBegin() 拿） */
    private int line;

    /** 规则类型（决定后续 RAG 召回哪类规范条目） */
    private RuleType ruleType;

    /** 严重度（决定展示顺序和处理优先级） */
    private Severity severity;

    /** 问题描述（给开发者看的，简短直接） */
    private String message;

    /** 修改建议（可空的代码示例或建议方向） */
    private String suggestion;

    /**
     * 便捷构造方法（无建议）
     */
    public static RuleResult of(String file, int line, RuleType ruleType,
                                 Severity severity, String message) {
        return RuleResult.builder()
                .file(file)
                .line(line)
                .ruleType(ruleType)
                .severity(severity)
                .message(message)
                .build();
    }
}