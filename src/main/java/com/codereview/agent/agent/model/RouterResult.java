package com.codereview.agent.agent.model;

import lombok.Builder;
import lombok.Data;

/**
 * Router 分类结果
 */
@Data
@Builder
public class RouterResult {

    /** 分类类型 */
    private TaskType taskType;

    /** LLM 原始回复（调试用） */
    private String rawResponse;

    /** 分类耗时（毫秒） */
    private long costMs;
}
