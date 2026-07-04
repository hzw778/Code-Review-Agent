package com.codereview.agent.rag.model;

import lombok.Builder;
import lombok.Data;

/**
 * 代码分块模型
 *
 * <p>一个 CodeChunk 代表一个完整方法，是代码 RAG 的最小检索单元。
 * 相比 RuleChunk，多了方法签名和行号，便于 Agent 回答时引用具体位置。
 */
@Data
@Builder
public class CodeChunk {

    /** 方法体源码（含签名和大括号） */
    private String content;

    /** 来源文件名 */
    private String source;

    /** 所在类名 */
    private String className;

    /** 方法名 */
    private String methodName;

    /** 完整签名（如 public User findById(String id)） */
    private String signature;

    /** 方法起始行号 */
    private int startLine;

    /** 方法结束行号 */
    private int endLine;

    /** chunk 序号 */
    private int index;
}
