package com.codereview.agent.tool;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具注册中心
 *
 * <p>启动时自动扫描所有 AgentTool 实现类（Spring 注入），注册到内存 Map。
 * Agent 引擎通过本类获取工具描述（喂给 LLM）和执行工具。
 *
 * <p>设计优势：新增工具只需实现 AgentTool 接口 + @Component，
 * 无需修改 ToolRegistry（开闭原则）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    // AgentTool 实现类列表（Spring 注入）
    private final List<AgentTool> tools;

    // 工具名 -> AgentTool 映射表（启动时初始化）
    private final Map<String, AgentTool> toolMap = new HashMap<>();

    /**
     * 初始化工具注册表
     */
    @PostConstruct
    public void init(){
        for (AgentTool tool : tools) {
            String name = tool.getName();
            if (toolMap.containsKey(name)) {
                log.error("[ToolRegistry] 工具名冲突: {}", name);
                throw new IllegalStateException("工具名重复: " + name);
            }
            toolMap.put(name, tool);
            log.info("[ToolRegistry] 注册工具: {} - {}", name, tool.getDescription());
        }
        log.info("[ToolRegistry] 工具注册完成, 共{}个工具", toolMap.size());
    }

    /**
     * 获取工具描述（喂给 LLM）
     *
     * @param toolName 工具名
     * @return 工具描述
     */
    public AgentTool getTool(String toolName) {
        return toolMap.get(toolName);
    }

    /**
     * 生成所有工具的描述文本（喂给 LLM 用）。
     *
     * <p>格式：
     * <pre>
     * 1. GitDiffTool: 获取 commit 的 diff
     * 可用工具：
     *    参数: {"commitId": "commit hash"}
     * 2. AstAnalysisTool: ...
     * </pre>
     */
    public String getToolDescriptions() {
        return tools.stream()
                .map(t -> String.format("- %s: %s\n  参数: %s",
                        t.getName(), t.getDescription(), t.getParametersDescription()))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 获取所有工具名（用于校验 LLM 返回的 action 是否合法）。
     */
    public List<String> getToolNames() {
        return tools.stream().map(AgentTool::getName).toList();
    }
}
