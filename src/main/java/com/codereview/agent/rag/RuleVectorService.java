package com.codereview.agent.rag;

import com.codereview.agent.rag.model.RuleSearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 规范向量检索服务
 *
 * <p>核心职责：把查询文本（通常是 Agent 检测到的代码片段）转成向量，
 * 在 ES 中检索最相似的规范条目，返回带相似度分数的结果列表。
 *
 * <p>Agent 调用流程：
 * <ol>
 *   <li>AST 分析检测到代码问题（如空 catch）</li>
 *   <li>把问题代码作为 query 调用 search()</li>
 *   <li>本服务返回相关规范条目</li>
 *   <li>Agent 把规范条目喂给 LLM 生成审查意见</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleVectorService {

    private final VectorStore vectorStore;

    /** 默认召回条数（topK） */
    private static final int DEFAULT_TOP_K = 3;

    /** 默认相似度阈值（低于此值不返回，避免召回无关规则） */
    private static final double DEFAULT_THRESHOLD = 0.5;

    /** metadata 中 docType 字段名 */
    private static final String META_DOC_TYPE = "docType";

    /** metadata 中 docType=rule 的过滤表达式（只检索规范文档） */
    private static final Filter.Expression RULE_FILTER =
            new FilterExpressionBuilder().eq(META_DOC_TYPE, "rule").build();

    /**
     * 检索相关规范（使用默认参数：topK=3, threshold=0.5, 只查 rule 类型）。
     *
     * @param query 查询文本（通常是代码片段或代码问题描述）
     * @return 相似规范列表，按相似度降序
     */
    public List<RuleSearchResult> search(String query) {
        return search(query, DEFAULT_TOP_K, DEFAULT_THRESHOLD);
    }

    /**
     * 检索相关规范（可自定义 topK 和阈值）。
     *
     * @param query     查询文本
     * @param topK      召回条数
     * @param threshold 相似度阈值（0~1）
     * @return 相似规范列表，按相似度降序
     */
    public List<RuleSearchResult> search(String query, int topK, double threshold) {
        log.info("[检索] 开始向量检索, query长度={}, topK={}, threshold={}",
                query.length(), topK, threshold);
        long start = System.currentTimeMillis();

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(threshold)
                .filterExpression(RULE_FILTER)
                .build();

        List<Document> documents = vectorStore.similaritySearch(request);

        List<RuleSearchResult> results = toResults(documents);

        log.info("[检索] 检索完成, 召回={}条, 耗时={}ms", results.size(), System.currentTimeMillis() - start);
        if (results.isEmpty()) {
            log.warn("[检索] 未召回任何规范, 请检查阈值是否过高或知识库是否为空");
        } else {
            results.forEach(r -> log.info("[检索] 命中 ruleName={}, score={}, category={}",
                    r.getRuleName(), r.getScore(), r.getCategory()));
        }
        return results;
    }

    /**
     * 把 Spring AI Document 转成 RuleSearchResult。
     */
    private List<RuleSearchResult> toResults(List<Document> documents) {
        List<RuleSearchResult> results = new ArrayList<>();
        if (documents == null || documents.isEmpty()) {
            return results;
        }
        for (Document doc : documents) {
            Map<String, Object> metadata = doc.getMetadata();
            results.add(RuleSearchResult.builder()
                    .content(doc.getText())
                    .source((String) metadata.getOrDefault("source", ""))
                    .category((String) metadata.getOrDefault("category", ""))
                    .ruleName((String) metadata.getOrDefault("ruleName", ""))
                    .score(doc.getScore() != null ? doc.getScore() : 0.0)
                    .build());
        }
        return results;
    }
}