package com.codereview.agent.rag.model;

import lombok.Builder;
import lombok.Data;

/**
 * 规范文档分块模型
 *
 * <p>一个 RuleChunk 代表规范文档中"一条规则"的完整内容，
 * 是 RAG 检索的最小知识单元。检索时由 Embedding 转成向量存入 ES，
 * Agent 回答时召回的也是这个粒度。</p>
 */
@Data
@Builder
public class RuleChunk {

    /** chunk 正文：规则全文（含错误示例、正确示例、原因） */
    private String content;

    /** 来源文件名，如 alibaba-java-style.md */
    private String source;

    /** 一级标题（规则大类），如"异常处理" */
    private String category;

    /** 二级标题（具体规则名），如"禁止空的 catch 块" */
    private String ruleName;

    /** chunk 在文件中的序号（从 0 开始，调试与日志追踪用） */
    private int index;
}