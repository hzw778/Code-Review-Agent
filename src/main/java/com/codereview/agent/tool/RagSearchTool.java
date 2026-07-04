package com.codereview.agent.tool;

import com.codereview.agent.rag.CodeVectorService;
import com.codereview.agent.rag.RuleVectorService;
import com.codereview.agent.rag.model.CodeSearchResult;
import com.codereview.agent.rag.model.RuleSearchResult;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 检索工具（Agent 可调用的统一入口）
 *
 * <p>核心职责：封装 RuleVectorService 和 CodeVectorService，
 * 给 Agent 引擎提供统一的规范检索 + 代码检索能力。
 *
 * <p>设计原则：
 * <ul>
 *   <li>混合粒度：rule 和 code 检索在同一工具类，作为两个方法</li>
 *   <li>简单输入：参数用 String/int，便于 LLM 生成调用参数</li>
 *   <li>结构化输出：返回对象列表，便于 Agent 引擎序列化</li>
 * </ul>
 *
 * <p>调用方：阶段 5 的 Agent Loop 会在 AST 检测到问题后，
 * 调用本工具检索相关规范和代码示例，作为 LLM 生成审查意见的依据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagSearchTool {

    private final RuleVectorService ruleVectorService;
    private final CodeVectorService codeVectorService;

    /**
     * 检索相关规范条目
     *
     * <p>适用场景：Agent 检测到代码问题后，需要给出规范依据时调用。
     *
     * @param query 查询文本（通常是问题描述，如"空 catch 块吞掉异常"）
     * @return 相似规范列表，按相似度降序
     */
    public List<RuleSearchResult> searchRules(String query){
        List<RuleSearchResult> results = ruleVectorService.search(query);
        log.info("[RAG工具] 检索规范完成, query={}, 结果数={}", query, results.size());
        return results;
    }

    /**
     * 检索相关代码示例
     *
     * <p>适用场景：Agent 需要给用户展示正确写法参考时调用。
     * 建议用代码片段作为 query，跨语言检索相似度更高。
     *
     * @param query 查询文本（通常是代码片段或方法签名）
     * @return 相似代码示例列表，按相似度降序
     */
    public List<CodeSearchResult> searchCodes(String query) {
        log.info("[RAG工具] 检索代码, query长度={}", query.length());
        return codeVectorService.search(query);
    }

    /**
     * 组合检索：同时查规范和代码（串行策略的便捷封装）
     *
     * <p>适用场景：Agent 一次性拿到规范依据 + 代码参考，
     * 减少工具调用次数。
     *
     * @param query 查询文本
     * @return 组合结果
     */
    public SearchResult searchAll(String query) {
        log.info("[RAG工具] 组合检索, query长度={}", query.length());
        List<RuleSearchResult> rules = ruleVectorService.search(query);
        List<CodeSearchResult> codes = codeVectorService.search(query);
        return SearchResult.builder()
                .rules(rules)
                .codes(codes)
                .build();
    }

    /**
     * 组合检索结果模型
     */
    @Data
    @Builder
    public static class SearchResult {
        /** 召回的规范列表 */
        private List<RuleSearchResult> rules;
        /** 召回的代码示例列表 */
        private List<CodeSearchResult> codes;
    }
}
