package com.codereview.agent.rag;

import com.codereview.agent.config.VectorStoreConfig;
import com.codereview.agent.rag.model.CodeChunk;
import com.codereview.agent.rag.model.CodeSearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码向量服务：代码示例入库 + 相似检索。
 *
 * <p>与 RuleVectorService 对称，但知识类型为 code。
 * 检索时用 docType=code 过滤，避免和规范库混淆。
 *
 * <p>Agent 双知识库协同：
 * <ol>
 *   <li>Agent 先用问题描述查 RuleVectorService（拿规则依据）</li>
 *   <li>再用规则名/代码片段查本服务（拿正确代码示例）</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeVectorService {

    private final CodeChunker codeChunker;
    private final VectorStore vectorStore;

    /** 代码示例扫描路径 */
    private static final String CODE_SAMPLES_DIR = "classpath:code-samples/*.java";

    /** Embedding 单次批量上限（阿里百炼限制 10） */
    private static final int EMBEDDING_BATCH_SIZE = 10;

    /** 默认召回条数 */
    private static final int DEFAULT_TOP_K = 3;

    /** 默认相似度阈值 */
    private static final double DEFAULT_THRESHOLD = 0.5;

    /** docType=code 的过滤表达式 */
    private static final Filter.Expression CODE_FILTER =
            new FilterExpressionBuilder().eq("docType", VectorStoreConfig.DOC_TYPE_CODE).build();

    /**
     * 入库所有代码示例文件。
     *
     * @return 入库的 chunk 总数
     */
    public int ingestAll() {
        log.info("[代码入库] ========== 代码示例入库开始 ==========");
        long start = System.currentTimeMillis();

        Resource[] resources;
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            resources = resolver.getResources(CODE_SAMPLES_DIR);
        } catch (IOException e) {
            log.error("[代码入库] 扫描代码示例目录失败", e);
            return 0;
        }
        if (resources.length == 0) {
            log.warn("[代码入库] 未找到代码示例, 请检查 classpath:code-samples/ 目录");
            return 0;
        }
        log.info("[代码入库] 扫描到 {} 个代码示例文件", resources.length);

        int total = 0;
        for (Resource resource : resources) {
            String sourceName = resource.getFilename();
            try {
                Path path = resource.getFile().toPath();
                List<CodeChunk> chunks = codeChunker.chunk(path, sourceName);
                List<Document> documents = toDocuments(chunks);
                batchAdd(documents);
                total += chunks.size();
                log.info("[代码入库] 文件[{}]入库完成, chunk数={}, 累计={}",
                        sourceName, chunks.size(), total);
            } catch (Exception e) {
                log.error("[代码入库] 文件[{}]入库失败: {}", sourceName, e.getMessage(), e);
            }
        }
        log.info("[代码入库] ========== 代码示例入库完成, 总chunk数={}, 耗时={}ms ==========",
                total, System.currentTimeMillis() - start);
        return total;
    }

    /**
     * 检索相关代码示例（使用默认参数）。
     */
    public List<CodeSearchResult> search(String query) {
        return search(query, DEFAULT_TOP_K, DEFAULT_THRESHOLD);
    }

    /**
     * 检索相关代码示例（可自定义参数）。
     */
    public List<CodeSearchResult> search(String query, int topK, double threshold) {
        log.info("[代码检索] 开始检索, query长度={}, topK={}, threshold={}",
                query.length(), topK, threshold);
        long start = System.currentTimeMillis();

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(threshold)
                .filterExpression(CODE_FILTER)
                .build();

        List<Document> documents = vectorStore.similaritySearch(request);
        List<CodeSearchResult> results = toResults(documents);

        log.info("[代码检索] 检索完成, 召回={}条, 耗时={}ms", results.size(), System.currentTimeMillis() - start);
        results.forEach(r -> log.info("[代码检索] 命中 {}.{}(), score={}",
                r.getClassName(), r.getMethodName(), r.getScore()));
        return results;
    }

    /**
     * CodeChunk 列表转 Document 列表（塞 metadata）。
     */
    private List<Document> toDocuments(List<CodeChunk> chunks) {
        List<Document> docs = new ArrayList<>(chunks.size());
        for (CodeChunk chunk : chunks) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", chunk.getSource());
            metadata.put("className", chunk.getClassName());
            metadata.put("methodName", chunk.getMethodName());
            metadata.put("signature", chunk.getSignature());
            metadata.put("startLine", chunk.getStartLine());
            metadata.put("endLine", chunk.getEndLine());
            metadata.put("docType", VectorStoreConfig.DOC_TYPE_CODE);
            docs.add(new Document(chunk.getContent(), metadata));
        }
        return docs;
    }

    /**
     * 分批入库（避免超过 Embedding 批量上限）。
     */
    private void batchAdd(List<Document> documents) {
        int total = documents.size();
        int from = 0;
        while (from < total) {
            int to = Math.min(from + EMBEDDING_BATCH_SIZE, total);
            vectorStore.add(documents.subList(from, to));
            from = to;
        }
    }

    /**
     * Document 列表转 CodeSearchResult 列表。
     */
    private List<CodeSearchResult> toResults(List<Document> documents) {
        List<CodeSearchResult> results = new ArrayList<>();
        if (documents == null || documents.isEmpty()) {
            return results;
        }
        for (Document doc : documents) {
            Map<String, Object> m = doc.getMetadata();
            results.add(CodeSearchResult.builder()
                    .content(doc.getText())
                    .source((String) m.getOrDefault("source", ""))
                    .className((String) m.getOrDefault("className", ""))
                    .methodName((String) m.getOrDefault("methodName", ""))
                    .signature((String) m.getOrDefault("signature", ""))
                    .startLine(toInt(m.get("startLine")))
                    .endLine(toInt(m.get("endLine")))
                    .score(doc.getScore() != null ? doc.getScore() : 0.0)
                    .build());
        }
        return results;
    }

    /**
     * 安全转换行号（ES 返回的可能是 Integer 或 Long）。
     */
    private int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).intValue();
        return 0;
    }
}
