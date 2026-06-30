package com.codereview.agent.ast;

import com.codereview.agent.ast.model.RuleResult;
import com.codereview.agent.ast.model.Severity;
import com.codereview.agent.git.model.DiffEntry;
import com.codereview.agent.git.model.DiffFile;
import com.codereview.agent.tool.AstAnalysisTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class AstAnalysisToolTest {

    @Autowired
    private AstAnalysisTool astAnalysisTool;

    @Test
    void analyzeFiles_batchAnalysis_shouldAggregateResults() {
        // 准备测试文件（单个 BadCode.java）
        File file = new File("src/test/sample/BadCode.java");
        List<File> files = List.of(file);

        // 批量分析
        List<RuleResult> results = astAnalysisTool.analyzeFiles(files);

        // 验证：应该有多个问题（不止空 catch，还有命名、魔法数字等）
        assertTrue(results.size() > 0);

        // 验证：按严重度排序（CRITICAL > MAJOR > MINOR > INFO）
        for (int i = 0; i < results.size() - 1; i++) {
            Severity current = results.get(i).getSeverity();
            Severity next = results.get(i + 1).getSeverity();
            assertTrue(current.getWeight() >= next.getWeight(),
                    "严重度排序错误：" + current + " 应该 >= " + next);
        }

        // 按文件分组统计
        Map<String, List<RuleResult>> byFile = astAnalysisTool.groupByFile(results);
        System.out.println("===== 按文件分组 =====");
        byFile.forEach((file2, list) -> System.out.printf("%s: %d 条%n",
                new File(file2).getName(), list.size()));

        // 按严重度分组统计
        Map<Severity, List<RuleResult>> bySeverity = astAnalysisTool.groupBySeverity(results);
        System.out.println("===== 按严重度分组 =====");
        bySeverity.forEach((sev, list) -> System.out.printf("%s: %d 条%n", sev, list.size()));

        System.out.println("===== 全部问题 =====");
        results.forEach(r -> System.out.printf("[%s] %s:%d %s%n",
                r.getSeverity(),
                new File(r.getFile()).getName(),
                r.getLine(),
                r.getMessage()));
    }

    @Test
    void analyzeDiff_withRepoPath_shouldExtractAndAnalyze() {
        // 构造一个简单的 DiffEntry 测试
        DiffEntry diff = new DiffEntry();
        diff.setCommitId("test-commit");
        diff.setAuthor("tester");

        DiffFile file = new DiffFile();
        file.setOldPath("/dev/null");
        file.setNewPath("src/test/sample/BadCode.java");  // 相对路径
        file.setChangeType("ADD");
        diff.setFiles(List.of(file));

        // 调用：传入项目根路径作为仓库路径（因为测试文件在项目里）
        String repoPath = System.getProperty("user.dir");
        List<RuleResult> results = astAnalysisTool.analyzeDiff(repoPath, diff);

        // 验证：应该能提取到 BadCode.java 并分析
        assertTrue(results.size() > 0);
        assertTrue(results.stream().allMatch(r -> r.getFile().contains("BadCode.java")));
    }

    @Test
    void analyzeDiff_noJavaFiles_shouldReturnEmpty() {
        // 构造一个只含非 Java 文件的 diff
        DiffEntry diff = new DiffEntry();
        DiffFile file = new DiffFile();
        file.setOldPath("/dev/null");
        file.setNewPath("README.md");
        file.setChangeType("ADD");
        diff.setFiles(List.of(file));

        List<RuleResult> results = astAnalysisTool.analyzeDiff(System.getProperty("user.dir"), diff);

        // 应该返回空列表（没有 Java 文件）
        assertTrue(results.isEmpty());
    }
}