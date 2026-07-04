package com.codereview.agent;

import com.codereview.agent.rag.model.CodeSearchResult;
import com.codereview.agent.rag.model.RuleSearchResult;
import com.codereview.agent.tool.RagSearchTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * RagSearchTool 集成测试
 * 前置条件：rule 和 code 数据已入库
 */
@SpringBootTest
class RagSearchToolTest {

    @Autowired
    private RagSearchTool ragSearchTool;

    @Test
    void searchRules_shouldReturnRules() {
        String query = "try { readFile(); } catch (Exception e) { }";
        List<RuleSearchResult> rules = ragSearchTool.searchRules(query);

        assertFalse(rules.isEmpty(), "应召回规范");
        System.out.println("===== 规范检索结果 =====");
        rules.forEach(r -> System.out.printf(
                "score=%.4f | %s > %s%n",
                r.getScore(), r.getCategory(), r.getRuleName()));
    }

    @Test
    void searchCodes_shouldReturnCodeExamples() {
        String query = "catch (Exception e) { log.error(\"处理失败\", e); throw e; }";
        List<CodeSearchResult> codes = ragSearchTool.searchCodes(query);

        assertFalse(codes.isEmpty(), "应召回代码示例");
        System.out.println("===== 代码检索结果 =====");
        codes.forEach(c -> System.out.printf(
                "score=%.4f | %s.%s()%n",
                c.getScore(), c.getClassName(), c.getMethodName()));
    }

    @Test
    void searchAll_shouldReturnBoth() {
        String query = "catch (Exception e) { }";
        RagSearchTool.SearchResult result = ragSearchTool.searchAll(query);

        System.out.println("===== 组合检索结果 =====");
        System.out.println("规范: " + result.getRules().size() + " 条");
        System.out.println("代码: " + result.getCodes().size() + " 条");
    }
}
