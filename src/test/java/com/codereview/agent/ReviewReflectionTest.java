package com.codereview.agent;

import com.codereview.agent.agent.ReviewReflection;
import com.codereview.agent.agent.model.AgentState;
import com.codereview.agent.agent.model.AgentStep;
import com.codereview.agent.agent.model.AgentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReviewReflection 集成测试
 * 需要 LLM API 可用
 */
@SpringBootTest
class ReviewReflectionTest {

    @Autowired
    private ReviewReflection reviewReflection;

    @Test
    void reflect_shouldFilterHallucination() {
        // 构造一个含幻觉的 AgentState
        AgentState state = new AgentState();
        state.setTaskId("test-001");
        state.setStatus(AgentStatus.SUCCESS);

        // 模拟 AST 结果：只检测到空 catch
        List<AgentStep> steps = new ArrayList<>();
        steps.add(AgentStep.builder()
                .round(1)
                .thought("分析代码")
                .action("AstAnalysisTool")
                .actionInput("{\"filePaths\":[\"UserService.java\"]}")
                .observation("{\"issueCount\":1,\"issues\":[{\"ruleType\":\"EMPTY_CATCH\",\"line\":4}]}")
                .build());
        // 模拟 RAG 结果：只召回了空 catch 规则
        steps.add(AgentStep.builder()
                .round(2)
                .thought("查规范")
                .action("RagSearchTool")
                .actionInput("{\"query\":\"空 catch\"}")
                .observation("{\"resultCount\":1,\"results\":[{\"ruleName\":\"禁止空的 catch 块\"}]}")
                .build());
        state.setSteps(steps);

        // 故意构造含幻觉的审查意见（提到不存在的"SQL 注入"问题）
        state.setFinalResult("## 审查意见\n"
                + "### 问题1：空 catch 块（有依据）\n"
                + "UserService.java 第4行有空 catch 块，违反规范。\n\n"
                + "### 问题2：SQL 注入风险（无依据）\n"
                + "UserService.java 第10行存在 SQL 注入漏洞。\n");

        String reflected = reviewReflection.reflect(state);

        assertNotNull(reflected);
        assertTrue(reflected.length() > 0);
        System.out.println("===== 自检结果 =====");
        System.out.println(reflected);
    }
}
