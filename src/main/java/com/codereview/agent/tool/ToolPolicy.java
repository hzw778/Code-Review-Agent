package com.codereview.agent.tool;

/**
 * 工具执行策略
 *
 * <p>定义单个工具的执行约束：超时、重试、是否启用。
 * ToolExecutor 在执行工具前查询此策略并强制应用。
 *
 * @param timeoutMs    单次执行超时（毫秒），超时则返回错误 JSON，不让工具卡死 Agent 线程
 * @param maxRetries   最大重试次数（仅对非超时异常重试；超时通常重试也会超时，不重试）
 * @param retryDelayMs 重试间隔（毫秒），简单退避
 * @param enabled      是否启用此工具（false 时 Agent 调用会直接返回错误，可用于灰度/熔断）
 */
public record ToolPolicy(long timeoutMs, int maxRetries, long retryDelayMs, boolean enabled) {

    /** 默认策略：60s 超时、不重试、启用 */
    public static ToolPolicy defaultPolicy() {
        return new ToolPolicy(60_000L, 0, 1_000L, true);
    }

    /** 构建器风格的工厂方法，便于按工具定制 */
    public static ToolPolicy of(long timeoutMs, int maxRetries, long retryDelayMs) {
        return new ToolPolicy(timeoutMs, maxRetries, retryDelayMs, true);
    }
}
