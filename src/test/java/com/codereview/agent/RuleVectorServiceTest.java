package com.codereview.agent;

import com.codereview.agent.rag.RuleVectorService;
import com.codereview.agent.rag.model.RuleSearchResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * RuleVectorService 集成测试
 * 前置条件：知识库已入库（先跑 KnowledgeIngestServiceTest）
 */
@SpringBootTest
class RuleVectorServiceTest {

    @Autowired
    private RuleVectorService ruleVectorService;

    @Test
    void search_emptyCatch_shouldReturnCatchRule() {
        // 模拟 Agent 检测到的代码问题：空 catch 块
        String query = "try { readFile(); } catch (Exception e) { }";

        List<RuleSearchResult> results = ruleVectorService.search(query);

        assertFalse(results.isEmpty(), "应至少召回一条规范");

        System.out.println("===== 检索结果 =====");
        System.out.println("查询: " + query);
        results.forEach(r -> System.out.printf(
                "score=%.4f | %s > %s%n  内容预览: %s%n",
                r.getScore(),
                r.getCategory(),
                r.getRuleName(),
                r.getContent().substring(0, Math.min(80, r.getContent().length()))));
    }

    @Test
    void search_magicNumber_shouldReturnMagicNumberRule() {
        // 模拟魔法数字问题
        String query = "int timeout = 86400;";

        List<RuleSearchResult> results = ruleVectorService.search(query);

        assertFalse(results.isEmpty());
        System.out.println("===== 检索结果 =====");
        System.out.println("查询: " + query);
        results.forEach(r -> System.out.printf(
                "score=%.4f | %s > %s%n",
                r.getScore(),
                r.getCategory(),
                r.getRuleName()));
    }
}