package com.codereview.agent.rag.model;

import lombok.Builder;
import lombok.Data;

/**
 * 规范向量检索结果
 *
 * <p>相比 RuleChunk 多了 score 字段（相似度分数，0~1，越大越相似）。
 * Agent 据此判断规则与代码的相关程度。
 */
@Data
@Builder
public class RuleSearchResult {

    /** chunk 正文 */
    private String content;

    /** 来源文件名 */
    private String source;

    /** 一级标题（规则大类） */
    private String category;

    /** 二级标题（具体规则名） */
    private String ruleName;

    /** 相似度分数（0~1，越大越相关） */
    private double score;
}