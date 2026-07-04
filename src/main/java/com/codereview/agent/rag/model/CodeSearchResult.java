package com.codereview.agent.rag.model;

import lombok.Builder;
import lombok.Data;

/**
 * 代码向量检索结果
 *
 * <p>相比 CodeChunk 多了 score 字段，Agent 据此判断代码示例的相关程度。
 */
@Data
@Builder
public class CodeSearchResult {

    /** 方法体源码 */
    private String content;

    /** 来源文件名 */
    private String source;

    /** 所在类名 */
    private String className;

    /** 方法名 */
    private String methodName;

    /** 完整签名 */
    private String signature;

    /** 起始行号 */
    private int startLine;

    /** 结束行号 */
    private int endLine;

    /** 相似度分数（0~1） */
    private double score;
}
