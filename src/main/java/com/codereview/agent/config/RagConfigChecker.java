package com.codereview.agent.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * RAG 配置检查器
 * 应用启动完成后检查 RAG 关键组件是否就绪：
 * 1. EmbeddingModel 是否能正常调用
 * 2. VectorStore 是否配置成功
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagConfigChecker {

    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;

    @EventListener(ApplicationReadyEvent.class)
    public void checkRagConfig() {
        log.info("[RAG检查] ========== RAG 配置检查开始 ==========");

        // 1. 检查 EmbeddingModel
        try {
            String modelName = embeddingModel.getClass().getSimpleName();
            log.info("[RAG检查] EmbeddingModel 已注入: {}", modelName);

            // 实际调用一次 embedding，验证 API 可用
            float[] embedding = embeddingModel.embed("测试向量");
            log.info("[RAG检查] Embedding 调用成功, 返回维度={}", embedding.length);

            // 验证维度是否和配置一致
            if (embedding.length != VectorStoreConfig.EMBEDDING_DIMENSION) {
                log.warn("[RAG检查] 警告: 实际维度({}) != 配置维度({}), 请检查 VectorStoreConfig.EMBEDDING_DIMENSION",
                        embedding.length, VectorStoreConfig.EMBEDDING_DIMENSION);
            }
        } catch (Exception e) {
            log.error("[RAG检查] Embedding 调用失败, RAG 链路不可用! 请检查 API Key 和网络。错误: {}", e.getMessage());
        }

        // 2. 检查 VectorStore
        try {
            String storeName = vectorStore.getClass().getSimpleName();
            log.info("[RAG检查] VectorStore 已注入: {}, 索引名={}", storeName, VectorStoreConfig.INDEX_NAME);
        } catch (Exception e) {
            log.error("[RAG检查] VectorStore 配置失败! 请检查 ES 连接。错误: {}", e.getMessage());
        }

        log.info("[RAG检查] ========== RAG 配置检查完成 ==========");
    }
}
