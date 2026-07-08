package com.codereview.agent.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工具执行器
 *
 * <p>负责执行 LLM 选择的工具，做异常隔离和参数校验。
 * Agent 引擎不直接调 AgentTool，而是通过 ToolExecutor 间接调用——
 * 这样异常处理、日志、超时等横切逻辑集中在此处。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolExecutor {

    private final ToolRegistry toolRegistry;

    /**
     * 执行工具。
     *
     * @param toolName 工具名
     * @param args     LLM 生成的参数
     * @return 执行结果（JSON 字符串）；工具不存在或异常时返回错误信息
     */
    public String execute(String toolName, Map<String,Object> args){
        log.info("[ToolExecutor] 执行工具: {}, 参数: {}", toolName, args);
        long start = System.currentTimeMillis();

        //1、检验工具是否存在
        AgentTool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            String errorMsg = String.format("工具不存在: %s", toolName);
            log.error("[ToolExecutor] {}", errorMsg);
            return String.format("{\"error\": \"%s\"}", errorMsg);
        }

        // 2. 执行（异常隔离，不让单个工具崩溃拖垮整个 Agent）
        try {
            String result = tool.execute(args);
            long cost = System.currentTimeMillis() - start;
            log.info("[ToolExecutor] 工具[{}]执行完成, 耗时={}ms, 结果长度={}",
                    toolName, cost, result != null ? result.length() : 0);
            return result;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("[ToolExecutor] 工具[{}]执行异常, 耗时={}ms", toolName, cost, e);
            // 返回错误信息而不是抛异常，让 Agent 能继续运行
            return "{\"error\": \"工具执行失败: " + e.getMessage() + "\"}";
        }
    }

}
