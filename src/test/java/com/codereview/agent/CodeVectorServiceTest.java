package com.codereview.agent;

import com.codereview.agent.rag.CodeVectorService;
import com.codereview.agent.rag.model.CodeSearchResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CodeVectorService 集成测试
 * 前置条件：ES 服务和 LLM API 可用
 */
@SpringBootTest
class CodeVectorServiceTest {

    @Autowired
    private CodeVectorService codeVectorService;

    @Test
    void ingestAll_shouldReturnPositiveChunkCount() {
        int total = codeVectorService.ingestAll();
        assertTrue(total > 0, "代码入库 chunk 数应大于 0");
        System.out.println("===== 代码入库结果 =====");
        System.out.println("总 chunk 数: " + total);
    }

    @Test
    void search_exceptionHandling_shouldReturnCodeExample() {
        // 注意：不要在这里重复调 ingestAll，会导致 ES 数据重复（幂等性问题）
        // 前置条件：先单独跑 ingestAll_shouldReturnPositiveChunkCount 入库一次

        // 查询：异常处理的正确写法
        // 注意：中文 query 与英文代码的 embedding 相似度天然偏低（跨语言检索），
        // 这里用代码片段作为 query 更贴近实际 Agent 使用场景，并适当降低阈值
        String query = "catch (Exception e) { log.error(\"处理失败\", e); throw e; }";
        List<CodeSearchResult> results = codeVectorService.search(query, 3, 0.3);

        assertFalse(results.isEmpty(), "应召回代码示例");

        System.out.println("===== 代码检索结果 =====");
        System.out.println("查询: " + query);
        results.forEach(r -> {
            System.out.printf("score=%.4f | %s.%s() 行号=%d-%d%n",
                    r.getScore(), r.getClassName(), r.getMethodName(),
                    r.getStartLine(), r.getEndLine());
            System.out.println("  签名: " + r.getSignature());
            System.out.println("  内容预览: " + r.getContent().substring(0,
                    Math.min(100, r.getContent().length())));
            System.out.println();
        });
    }
}
