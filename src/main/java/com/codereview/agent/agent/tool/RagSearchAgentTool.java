package com.codereview.agent.agent.tool;

import com.codereview.agent.rag.model.RuleSearchResult;
import com.codereview.agent.tool.AgentTool;
import com.codereview.agent.tool.RagSearchTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * RAG 检索工具：从知识库检索相关规范和代码示例。
 *
 * <p>包装阶段4的 RagSearchTool，供 Agent ReAct 循环调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagSearchAgentTool implements AgentTool {

    private final RagSearchTool ragSearchTool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "RagSearchTool";
    }

    @Override
    public String getDescription() {
        return "从知识库检索相关规范条目和代码示例。当 AST 检测到代码问题后，用此工具查询相关规范依据，为审查意见提供权威支持。";
    }

    @Override
    public String getParametersDescription() {
        return "{\"query\": " +
               "\"检索关键词或代码问题描述（必填，建议用代码片段或问题类型描述）\"}";
    }

    @Override
    public String execute(Map<String, Object> args) {
        String query = (String) args.get("query");

        log.info("[RagSearchTool] 检索, query={}", query);

        List<RuleSearchResult> results = ragSearchTool.searchRules(query);

        try {
            return objectMapper.writeValueAsString(Map.of(
                    "query", query,
                    "resultCount", results.size(),
                    "results", results
            ));
        } catch (Exception e) {
            log.error("[RagSearchTool] 序列化失败", e);
            return "{\"error\": \"序列化检索结果失败\"}";
        }
    }
}
