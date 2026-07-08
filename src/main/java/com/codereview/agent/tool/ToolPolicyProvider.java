package com.codereview.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工具策略提供者
 *
 * <p>为每个工具提供 ToolPolicy（超时/重试/启用）。
 * 当前为代码内置默认值，后续可改为 @ConfigurationProperties 从 application.yml 读取。
 *
 * <p>策略按工具特性定制：
 * <ul>
 *   <li>GitDiffTool：克隆慢，超时 120s，重试 1 次（网络抖动）</li>
 *   <li>AstAnalysisTool：纯本地解析，超时 30s，不重试</li>
 *   <li>RagSearchTool：调 embedding API 易抖动，超时 30s，重试 2 次</li>
 *   <li>FileReadTool：本地 IO，超时 10s，不重试</li>
 *   <li>RuleMatchTool：批量调 embedding API，超时 60s（N 次检索），重试 2 次</li>
 *   <li>SimilarCodeTool：调 embedding API，超时 30s，重试 2 次（同 RagSearchTool）</li>
 * </ul>
 *
 * <p>设计经验：超时和重试要按工具特性差异化配置——
 * 网络密集型工具（git/embedding）给更长超时和重试，本地工具给短超时零重试。
 * 一刀切的策略要么误杀慢工具，要么让快工具卡太久。
 *
 * <p>RuleMatchTool 超时为什么是 60s 而非 30s：内部循环调 N 次 searchRules
 * （每个 ruleType 一次），总耗时是单次的 N 倍。N=5 个 ruleType × 单次 ~3s = 15s，
 * 给 60s 留余量。若 ruleType 多到超时，说明应该减少批量大小。
 */
@Slf4j
@Component
public class ToolPolicyProvider {

    private final Map<String, ToolPolicy> policies = Map.of(
            "GitDiffTool", ToolPolicy.of(120_000L, 1, 2_000L),
            "AstAnalysisTool", ToolPolicy.of(30_000L, 0, 1_000L),
            "RagSearchTool", ToolPolicy.of(30_000L, 2, 1_000L),
            "FileReadTool", ToolPolicy.of(10_000L, 0, 1_000L),
            "RuleMatchTool", ToolPolicy.of(60_000L, 2, 1_000L),
            "SimilarCodeTool", ToolPolicy.of(30_000L, 2, 1_000L)
    );

    private static final ToolPolicy DEFAULT = ToolPolicy.defaultPolicy();

    /**
     * 获取工具策略，未配置的用默认（60s/不重试/启用）。
     */
    public ToolPolicy getPolicy(String toolName) {
        return policies.getOrDefault(toolName, DEFAULT);
    }
}
