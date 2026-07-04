package com.codereview.agent;

import com.codereview.agent.rag.KnowledgeIngestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KnowledgeIngestService 集成测试
 * 需要 ES 服务和 LLM API 都可用
 */
@SpringBootTest
class KnowledgeIngestServiceTest {

    @Autowired
    private KnowledgeIngestService knowledgeIngestService;

    @Test
    void ingestAll_shouldReturnPositiveChunkCount() {
        int total = knowledgeIngestService.ingestAll();

        // 两个文档共 20 + 23 = 43 个 chunk
        assertTrue(total > 0, "入库 chunk 数应大于 0");
        System.out.println("===== 入库结果 =====");
        System.out.println("总 chunk 数: " + total);
    }
}