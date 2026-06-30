package com.codereview.agent.tool;

import com.codereview.agent.ast.AstAnalyzer;
import com.codereview.agent.ast.model.RuleResult;
import com.codereview.agent.ast.model.Severity;
import com.codereview.agent.git.model.DiffEntry;
import com.codereview.agent.git.model.DiffFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AST 分析工具（Agent 可调用的工具入口）
 * <p>
 * 核心职责：
 * 1. 批量分析多个 Java 文件，合并检测结果
 * 2. 集成 diff：从 DiffEntry 提取变更的 Java 文件，只分析这些文件
 * 3. 结果排序：按严重度从高到低排序（BLOCKER 最前）
 * </p>
 *
 * <p>调用方：阶段 5 的 Agent Loop 会通过此工具分析 commit diff 涉及的代码。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AstAnalysisTool {

    private final AstAnalyzer astAnalyzer;

    /**
     * 方式1：直接传入文件列表，批量分析
     * <p>适用场景：单元测试、手动指定文件分析</p>
     *
     * @param files Java 文件列表
     * @return 所有文件的问题列表（按严重度排序）
     */
    public List<RuleResult> analyzeFiles(List<File> files) {
        log.info("[AST工具] 批量分析开始, 文件数={}", files.size());

        long startTime = System.currentTimeMillis();
        List<RuleResult> allResults = new ArrayList<>();

        for (File file : files) {
            try {
                List<RuleResult> fileResults = astAnalyzer.analyze(file);
                allResults.addAll(fileResults);
            } catch (Exception e) {
                // 单个文件分析失败不影响其他文件
                log.warn("[AST工具] 单文件分析失败, 跳过, 文件={}, 错误={}",
                        file.getAbsolutePath(), e.getMessage());
            }
        }

        // 按严重度排序（BLOCKER 最前）
        sortBySeverity(allResults);

        long costMs = System.currentTimeMillis() - startTime;
        log.info("[AST工具] 批量分析完成, 文件数={}, 总问题数={}, 耗时={}ms",
                files.size(), allResults.size(), costMs);
        return allResults;
    }

    /**
     * 方式2：从 DiffEntry 提取变更的 Java 文件，自动分析
     * <p>适用场景：文件路径在 diff 中已经是绝对路径（少见）</p>
     *
     * @param diff commit diff
     * @return 所有变更 Java 文件的问题列表
     */
    public List<RuleResult> analyzeDiff(DiffEntry diff) {
        log.info("[AST工具] 基于 diff 分析开始, commitId={}, 变更文件数={}",
                diff.getCommitId(), diff.getFiles().size());

        // 从 diff 提取所有 Java 文件路径
        List<File> javaFiles = extractJavaFiles(diff);

        if (javaFiles.isEmpty()) {
            log.info("[AST工具] diff 中没有 Java 文件, 跳过分析, commitId={}", diff.getCommitId());
            return List.of();
        }

        log.info("[AST工具] 提取到 {} 个 Java 文件待分析", javaFiles.size());
        return analyzeFiles(javaFiles);
    }

    /**
     * 方式3：传入仓库本地路径 + diff，自动拼出完整文件路径并分析（Agent 主入口）
     * <p>适用场景：Agent 拿到仓库克隆路径 + commit diff，分析所有变更 Java 文件</p>
     *
     * @param repoLocalPath 仓库本地克隆路径（如 ./workdir/my-repo）
     * @param diff commit diff
     * @return 所有变更 Java 文件的问题列表
     */
    public List<RuleResult> analyzeDiff(String repoLocalPath, DiffEntry diff) {
        log.info("[AST工具] 基于仓库路径+diff 分析开始, repoPath={}, commitId={}, 变更文件数={}",
                repoLocalPath, diff.getCommitId(), diff.getFiles().size());

        // 从 diff 提取 Java 文件相对路径，拼上仓库本地路径
        List<File> javaFiles = extractJavaFiles(repoLocalPath, diff);

        if (javaFiles.isEmpty()) {
            log.info("[AST工具] diff 中没有 Java 文件, 跳过分析, commitId={}", diff.getCommitId());
            return List.of();
        }

        // 过滤掉不存在的文件（可能 diff 里有但本地没拉到）
        List<File> existingFiles = javaFiles.stream()
                .filter(File::exists)
                .collect(Collectors.toList());
        if (existingFiles.size() < javaFiles.size()) {
            log.warn("[AST工具] 部分文件不存在, 总数={}, 存在={}, commitId={}",
                    javaFiles.size(), existingFiles.size(), diff.getCommitId());
        }

        return analyzeFiles(existingFiles);
    }

    /**
     * 从 diff 提取所有 Java 文件（假设 diff 里的路径是绝对路径，少见场景）
     */
    private List<File> extractJavaFiles(DiffEntry diff) {
        return diff.getFiles().stream()
                .filter(this::isJavaFile)
                .map(DiffFile::getDisplayPath)   // 优先取新路径，删除文件取旧路径
                .map(File::new)
                .collect(Collectors.toList());
    }

    /**
     * 从 diff 提取所有 Java 文件，拼上仓库本地路径
     */
    private List<File> extractJavaFiles(String repoLocalPath, DiffEntry diff) {
        return diff.getFiles().stream()
                .filter(this::isJavaFile)
                .map(DiffFile::getDisplayPath)
                .map(relativePath -> new File(repoLocalPath, relativePath))
                .collect(Collectors.toList());
    }

    /**
     * 判断 diff 文件是否是 Java 文件
     * 注意：删除的文件 newPath 是 /dev/null，要用 getDisplayPath 判断
     */
    private boolean isJavaFile(DiffFile diffFile) {
        String path = diffFile.getDisplayPath();
        return path != null && path.endsWith(".java");
    }

    /**
     * 按严重度排序（BLOCKER 最前，INFO 最后）
     */
    private void sortBySeverity(List<RuleResult> results) {
        Comparator<RuleResult> bySeverity = Comparator.comparingInt(
                r -> r.getSeverity().getWeight());
        results.sort(bySeverity.reversed());  // 降序：BLOCKER 在前
    }

    /**
     * 按文件分组统计（用于报告展示）
     *
     * @return Map<文件路径, 问题列表>
     */
    public java.util.Map<String, List<RuleResult>> groupByFile(List<RuleResult> results) {
        return results.stream().collect(Collectors.groupingBy(RuleResult::getFile));
    }

    /**
     * 按严重度分组统计（用于报告展示）
     *
     * @return Map<严重度, 问题列表>
     */
    public java.util.Map<Severity, List<RuleResult>> groupBySeverity(List<RuleResult> results) {
        return results.stream().collect(Collectors.groupingBy(RuleResult::getSeverity));
    }
}