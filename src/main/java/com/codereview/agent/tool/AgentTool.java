package com.codereview.agent.tool;

import java.util.Map;

/**
 * Agent 工具接口
 *
 * <p>所有供 Agent ReAct 循环调用的工具都需实现此接口。
 * ToolRegistry 启动时自动扫描所有 AgentTool 实现类并注册。
 */
public interface AgentTool {

    /**
     * 工具名（唯一标识，LLM 通过此名选择工具）
     */
    String getName();

    /**
     * 工具描述（告诉 LLM 这个工具做什么、什么时候用）
     */
    String getDescription();

    /**
     * 参数描述（JSON 字符串，告诉 LLM 需要传什么参数）
     *
     * <p>格式示例：
     * <pre>
     * {
     *   "commitId": "commit 的 hash 值（必填）",
     *   "repoUrl": "仓库地址（选填）"
     * }
     * </pre>
     */
    String getParametersDescription();

    /**
     * 执行工具
     *
     * @param args LLM 生成的参数（key=参数名, value=参数值）
     * @return 执行结果（JSON 字符串，会作为 Observation 喂给 LLM）
     */
    String execute(Map<String, Object> args);
}
