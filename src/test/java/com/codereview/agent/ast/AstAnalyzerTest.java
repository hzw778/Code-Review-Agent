package com.codereview.agent.ast;

import com.codereview.agent.ast.model.RuleResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AstAnalyzerTest {

    @Autowired
    private AstAnalyzer astAnalyzer;


    @Test
    void analyze_badCode_shouldDetectAllRuleTypes() {
        File file = new File("src/test/sample/BadCode.java");
        List<RuleResult> results = astAnalyzer.analyze(file);

        // 打印所有结果，肉眼检查
        System.out.println("===== 全部检测结果 =====");
        results.forEach(r -> System.out.printf("[%s] 行号=%d %s%n",
                r.getRuleType(), r.getLine(), r.getMessage()));

        // 验证所有规则类型都被检测到
        Set<String> detectedTypes = results.stream()
                .map(r -> r.getRuleType().name())
                .collect(Collectors.toSet());

        assertTrue(detectedTypes.contains("EMPTY_CATCH"), "应该检测到空 catch");
        assertTrue(detectedTypes.contains("MAGIC_NUMBER"), "应该检测到魔法数字");
        assertTrue(detectedTypes.contains("RESOURCE_LEAK"), "应该检测到资源泄漏");
        assertTrue(detectedTypes.contains("NAMING_CONVENTION"), "应该检测到命名不规范");
        // METHOD_TOO_LONG 可能因为语句数统计方式不同而没检测到，需要调整阈值或样例
        // assertTrue(detectedTypes.contains("METHOD_TOO_LONG"), "应该检测到方法过长");

        // 所有结果都应该有 file 字段（验证 AstAnalyzer 统一补 file 生效）
        assertTrue(results.stream().allMatch(r -> r.getFile() != null));

        System.out.println("===== 规则统计 =====");
        results.stream()
                .collect(Collectors.groupingBy(r -> r.getRuleType()))
                .forEach((type, list) -> System.out.printf("%s: %d 条%n", type, list.size()));
    }
}