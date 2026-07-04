package com.codereview.agent.model;

import lombok.Builder;
import lombok.Data;

/**
 * 知识入库结果
 */
@Data
@Builder
public class KnowledgeIngestResult {

    /** 入库的规范 chunk 数 */
    private int ruleCount;

    /** 入库的代码 chunk 数 */
    private int codeCount;

    /** 总 chunk 数 */
    private int totalCount;
}
