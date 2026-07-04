package com.codereview.agent.rag;

import com.codereview.agent.config.VectorStoreConfig;
import com.codereview.agent.rag.model.RuleChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
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
 * 知识入库服务
 *
 * <p>核心职责：扫描 knowledge 目录下的规范文档，分块后批量写入 ES 向量库。
 *
 * <p>数据流：
 * <ol>
 *   <li>扫描 classpath:knowledge/*.md</li>
 *   <li>RuleChunker 按 ## 标题分块</li>
 *   <li>每个 chunk 转成 Document（content + metadata）</li>
 *   <li>VectorStore.add() 内部自动调 EmbeddingModel 转向量并写入 ES</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIngestService {

    private final RuleChunker ruleChunker;
    private final VectorStore vectorStore;

    /** 规范文档扫描路径（classpath 下的 knowledge 目录） */
    private static final String KNOWLEDGE_DIR = "classpath:knowledge/*.md";

    /**
     * Embedding 单次批量上限
     * 阿里百炼 text-embedding-v3 限制单次最多 10 条，这里设为 10
     */
    private static final int EMBEDDING_BATCH_SIZE = 10;

    /**
     * 入库所有规范文档。
     *
     * @return 入库的 chunk 总数
     */
    public int ingestAll() {
        log.info("[入库] ========== 知识入库开始 ==========");
        long start = System.currentTimeMillis();

        // 1. 扫描 knowledge 目录
        Resource[] resources;
        try {
            resources = scanKnowledgeFiles();
        } catch (IOException e) {
            log.error("[入库] 扫描规范文档目录失败, path={}", KNOWLEDGE_DIR, e);
            return 0;
        }
        if (resources.length == 0) {
            log.warn("[入库] 未找到规范文档, 请检查 classpath:knowledge/ 目录");
            return 0;
        }
        log.info("[入库] 扫描到 {} 个规范文档", resources.length);

        int totalChunks = 0;
        // 2. 逐个文件处理
        for (Resource resource : resources) {
            String sourceName = resource.getFilename();
            try {
                List<RuleChunk> chunks = chunkFile(resource, sourceName);
                List<Document> documents = toDocuments(chunks);
                // 3. 分批入库（阿里百炼 embedding 单次上限 10 条）
                batchAdd(documents);
                totalChunks += chunks.size();
                log.info("[入库] 文件[{}]入库完成, chunk数={}, 累计={}",
                        sourceName, chunks.size(), totalChunks);
            } catch (Exception e) {
                log.error("[入库] 文件[{}]入库失败: {}", sourceName, e.getMessage(), e);
            }
        }

        log.info("[入库] ========== 知识入库完成, 总chunk数={}, 耗时={}ms ==========",
                totalChunks, System.currentTimeMillis() - start);
        return totalChunks;
    }

    /**
     * 扫描 knowledge 目录下所有 .md 文件。
     */
    private Resource[] scanKnowledgeFiles() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        return resolver.getResources(KNOWLEDGE_DIR);
    }

    /**
     * 对单个文件分块。
     */
    private List<RuleChunk> chunkFile(Resource resource, String sourceName) throws IOException {
        Path path = resource.getFile().toPath();
        return ruleChunker.chunk(path, sourceName);
    }

    /**
     * 把 RuleChunk 列表转成 Spring AI Document 列表。
     *
     * <p>metadata 设计：
     * <ul>
     *   <li>source: 来源文件名，检索时按来源过滤</li>
     *   <li>category: 一级标题（规则大类），让 LLM 知道规则所属分类</li>
     *   <li>ruleName: 二级标题（具体规则名），让 LLM 能引用规则名</li>
     *   <li>docType: 文档类型（rule/code），后续检索时区分规范 vs 代码</li>
     * </ul>
     */
    private List<Document> toDocuments(List<RuleChunk> chunks) {
        List<Document> docs = new ArrayList<>(chunks.size());
        for (RuleChunk chunk : chunks) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", chunk.getSource());
            metadata.put("category", chunk.getCategory());
            metadata.put("ruleName", chunk.getRuleName());
            metadata.put("docType", VectorStoreConfig.DOC_TYPE_RULE);
            docs.add(new Document(chunk.getContent(), metadata));
        }
        return docs;
    }

    /**
     * 分批调用 VectorStore.add，避免超过 Embedding 服务的单次批量上限。
     *
     * @param documents 待入库文档列表
     */
    private void batchAdd(List<Document> documents) {
        int total = documents.size();
        int from = 0;
        while (from < total) {
            int to = Math.min(from + EMBEDDING_BATCH_SIZE, total);
            List<Document> batch = documents.subList(from, to);
            vectorStore.add(batch);
            log.info("[入库] 批次写入完成, 本批={}, 累计={}, 总数={}",
                    batch.size(), to, total);
            from = to;
        }
    }
}