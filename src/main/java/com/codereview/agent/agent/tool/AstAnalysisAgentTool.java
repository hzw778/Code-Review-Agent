package com.codereview.agent.agent.tool;

import com.codereview.agent.ast.model.RuleResult;
import com.codereview.agent.tool.AgentTool;
import com.codereview.agent.tool.AstAnalysisTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AST 分析工具：检测 Java 代码的结构问题。
 *
 * <p>包装阶段3的 AstAnalysisTool，供 Agent ReAct 循环调用。
 *
 * <p>参数设计：接收文件路径列表（LLM 从 GitDiffTool 返回的 files.newPath 中提取）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AstAnalysisAgentTool implements AgentTool {

    private final AstAnalysisTool astAnalysisTool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "AstAnalysisTool";
    }

    @Override
    public String getDescription() {
        return "分析 Java 源码的 AST 结构，检测代码问题（空 catch 块、方法过长、魔法数字、资源泄漏、命名规范）。"
                + "当 diff 包含 Java 文件时使用此工具检查代码质量。";
    }

    @Override
    public String getParametersDescription() {
        return "{\"filePaths\": [\"Java 文件绝对路径列表（必填，从 GitDiffTool 返回的 files.newPath 中提取，需拼接 repoLocalPath）\"]}";
    }

    @Override
    public String execute(Map<String, Object> args) {
        Object filePathsObj = args.get("filePaths");

        log.info("[AstAnalysisTool] 分析文件, filePaths={}", filePathsObj);

        if (filePathsObj == null) {
            return "{\"error\": \"filePaths 不能为空\"}";
        }

        List<String> filePaths = toStringList(filePathsObj);
        if (filePaths.isEmpty()) {
            return "{\"error\": \"filePaths 为空\"}";
        }

        // 转为 File 列表，过滤存在的 Java 文件
        List<File> javaFiles = filePaths.stream()
                .filter(p -> p.endsWith(".java"))
                .map(File::new)
                .filter(File::exists)
                .toList();

        if (javaFiles.isEmpty()) {
            return "{\"analyzedFileCount\": 0, \"issueCount\": 0, \"issues\": [], "
                    + "\"message\": \"没有找到 Java 文件或文件不存在\"}";
        }

        List<RuleResult> results = astAnalysisTool.analyzeFiles(javaFiles);

        try {
            return objectMapper.writeValueAsString(Map.of(
                    "analyzedFileCount", javaFiles.size(),
                    "issueCount", results.size(),
                    "issues", results
            ));
        } catch (Exception e) {
            log.error("[AstAnalysisTool] 序列化失败", e);
            return "{\"error\": \"序列化分析结果失败\"}";
        }
    }

    /**
     * 把 LLM 传入的参数转为 List<String>。
     */
    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object obj) {
        List<String> result = new ArrayList<>();
        if (obj instanceof List) {
            for (Object item : (List<Object>) obj) {
                result.add(String.valueOf(item));
            }
        } else if (obj instanceof String) {
            String s = (String) obj;
            if (s.contains(",")) {
                for (String p : s.split(",")) {
                    result.add(p.trim());
                }
            } else {
                result.add(s.trim());
            }
        }
        return result;
    }
}
