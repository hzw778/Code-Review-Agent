package com.codereview.agent.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具执行器
 *
 * <p>负责执行 LLM 选择的工具，做四层横切防护：
 * <ol>
 *   <li>存在性校验：工具是否注册</li>
 *   <li>启用校验：ToolPolicy.enabled，未启用的工具直接拒绝（灰度/熔断用）</li>
 *   <li>超时控制：单次执行超时则返回错误 JSON，防止卡死 Agent 线程</li>
 *   <li>重试：对非超时异常按 maxRetries 重试（超时通常重试也会超时，不重试）</li>
 * </ol>
 *
 * <p>Agent 引擎不直接调 AgentTool，而是通过 ToolExecutor 间接调用——
 * 异常处理、日志、超时、重试等横切逻辑集中在此处，工具实现类保持纯粹。
 *
 * <p>异常隔离原则：无论工具怎么崩，ToolExecutor 都返回 JSON 字符串
 * （成功是数据，失败是 {"error": "..."}），从不抛异常给 Agent 循环。
 * 这样 Agent 看到 error 后能自我修复（换工具/换参数），不会整个崩溃。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolExecutor {

    private final ToolRegistry toolRegistry;
    private final ToolPolicyProvider toolPolicyProvider;

    /**
     * 执行工具。
     *
     * @param toolName 工具名
     * @param args     LLM 生成的参数
     * @return 执行结果（JSON 字符串）；工具不存在/未启用/超时/异常时返回错误信息
     */
    public String execute(String toolName, Map<String, Object> args) {
        log.info("[ToolExecutor] 执行工具: {}, 参数: {}", toolName, args);
        long start = System.currentTimeMillis();

        // 1. 校验工具是否存在
        AgentTool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            String errorMsg = String.format("工具不存在: %s", toolName);
            log.error("[ToolExecutor] {}", errorMsg);
            return String.format("{\"error\": \"%s\"}", errorMsg);
        }

        // 2. 查询策略
        ToolPolicy policy = toolPolicyProvider.getPolicy(toolName);

        // 3. 启用校验
        if (!policy.enabled()) {
            log.warn("[ToolExecutor] 工具[{}]已被策略禁用", toolName);
            return "{\"error\": \"工具已被禁用: " + toolName + "\"}";
        }

        // 4. 带超时+重试执行
        int maxRetries = policy.maxRetries();
        Exception lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                String result = executeWithTimeout(tool, args, policy.timeoutMs());
                long cost = System.currentTimeMillis() - start;
                log.info("[ToolExecutor] 工具[{}]执行完成, 尝试={}/{}, 耗时={}ms, 结果长度={}",
                        toolName, attempt, maxRetries, cost, result != null ? result.length() : 0);
                return result;
            } catch (TimeoutException te) {
                // 超时不重试（重试大概率还是超时，浪费时间）
                long cost = System.currentTimeMillis() - start;
                log.error("[ToolExecutor] 工具[{}]执行超时, 耗时={}ms, timeoutMs={}", toolName, cost, policy.timeoutMs());
                return "{\"error\": \"工具执行超时(" + policy.timeoutMs() + "ms): " + toolName + "\"}";
            } catch (Exception e) {
                lastError = e;
                if (attempt < maxRetries) {
                    log.warn("[ToolExecutor] 工具[{}]第{}次执行失败, 将重试: {}", toolName, attempt + 1, e.getMessage());
                    sleep(policy.retryDelayMs());
                } else {
                    long cost = System.currentTimeMillis() - start;
                    log.error("[ToolExecutor] 工具[{}]执行异常(已耗尽{}次重试), 耗时={}ms", toolName, maxRetries, cost, e);
                }
            }
        }

        // 所有重试都失败，返回错误（不抛异常，让 Agent 继续）
        return "{\"error\": \"工具执行失败(重试" + maxRetries + "次): " +
                (lastError != null ? lastError.getMessage() : "未知错误") + "\"}";
    }

    /**
     * 单次带超时执行。
     *
     * <p>用 CompletableFuture.orTimeout 实现超时：在 worker 线程跑工具，
     * Agent 线程 join 等待。超时抛 TimeoutException。
     *
     * <p>注意：orTimeout 不会中断 worker 线程，超时后底层工具可能仍在跑
     * （如 git clone 仍在进行）。这是可接受的——孤儿线程最终会自己结束。
     * 生产级实现会用可中断的 IO 操作配合 Future.cancel(true)。
     */
    private String executeWithTimeout(AgentTool tool, Map<String, Object> args, long timeoutMs)
            throws TimeoutException {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> tool.execute(args));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException ee) {
            // 包装工具内部抛出的异常
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            throw new RuntimeException(cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("工具执行被中断: " + tool.getName(), ie);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
