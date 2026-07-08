package com.codereview.agent.agent.tool;

import com.codereview.agent.rag.model.CodeSearchResult;
import com.codereview.agent.tool.AgentTool;
import com.codereview.agent.tool.RagSearchTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 相似代码检索工具：从知识库检索相似代码示例（正确写法参考）。
 *
 * <p>与 RagSearchTool/RuleMatchTool 的区别：
 * <ul>
 *   <li>RagSearchTool：检索<b>规范条目</b>（rule，文字描述"应该怎么做"）</li>
 *   <li>RuleMatchTool：批量匹配<b>规范条目</b>（rule，基于 ruleType 精确匹配）</li>
 *   <li>SimilarCodeTool：检索<b>代码示例</b>（code，正确写法"长什么样"）</li>
 * </ul>
 *
 * <p>规范和代码示例互补：
 * <ul>
 *   <li>规范给依据："根据《阿里巴巴 Java 开发手册》异常处理-空 catch 块条目，不应吞掉异常"</li>
 *   <li>代码示例给参考："参考写法见 UserSample.java findById() 第 42-58 行，应记录日志并抛出"</li>
 * </ul>
 *
 * <p>参数设计——用 codeSnippet 而非 query：
 * 代码检索用代码片段作 query 匹配度更高（同语言 vs 跨语言）。
 * 中文 query（如"用户查询方法"）和英文代码（如"findById"）跨语言相似度天然衰减，
 * 用代码片段作 query 能提升检索准确率（project_memory 已记录此 lesson）。
 *
 * <p>Agent 工具设计经验——工具适配层模式：
 * 底层 RagSearchTool.searchCodes() 已实现检索逻辑，SimilarCodeTool 只做：
 * <ol>
 *   <li>参数适配：LLM 友好的简单参数（codeSnippet）→ 底层服务参数（query）</li>
 *   <li>结果格式化：CodeSearchResult 列表 → JSON（含 lineNumbers 便于引用溯源）</li>
 *   <li>错误隔离：异常转 error JSON，不抛给 Agent 循环</li>
 * </ol>
 * 工具类保持"薄包装"，复杂业务逻辑留在底层服务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SimilarCodeTool implements AgentTool {

    private final RagSearchTool ragSearchTool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "SimilarCodeTool";
    }

    @Override
    public String getDescription() {
        return "从知识库检索相似代码示例（正确写法参考）。"
                + "当需要给审查意见附带正确代码参考时使用此工具，"
                + "输入问题代码片段，返回知识库里的相似正确实现。";
    }

    @Override
    public String getParametersDescription() {
        return "{\"codeSnippet\": \"代码片段或方法签名（必填，建议用问题代码本身作为查询，"
                + "同语言检索比自然语言匹配度更高）\"}";
    }

    @Override
    public String execute(Map<String, Object> args) {
        Object snippetObj = args.get("codeSnippet");
        log.info("[SimilarCodeTool] 检索相似代码, codeSnippet长度={}",
                snippetObj != null ? String.valueOf(snippetObj).length() : 0);

        if (snippetObj == null) {
            return "{\"error\": \"codeSnippet 不能为空\"}";
        }
        String codeSnippet = String.valueOf(snippetObj).trim();
        if (codeSnippet.isEmpty()) {
            return "{\"error\": \"codeSnippet 不能为空\"}";
        }

        List<CodeSearchResult> results = ragSearchTool.searchCodes(codeSnippet);

        // 格式化结果（含 lineNumbers 便于 Agent 在审查意见里引用具体行号）
        List<Map<String, Object>> samples = new ArrayList<>();
        if (results != null) {
            for (CodeSearchResult c : results) {
                Map<String, Object> sample = new LinkedHashMap<>();
                sample.put("className", c.getClassName());
                sample.put("methodName", c.getMethodName());
                sample.put("signature", c.getSignature() != null ? c.getSignature() : "");
                sample.put("content", c.getContent());
                sample.put("source", c.getSource() != null ? c.getSource() : "");
                sample.put("lineNumbers", c.getStartLine() + "-" + c.getEndLine());
                sample.put("score", c.getScore());
                samples.add(sample);
                log.info("[SimilarCodeTool] 命中 {}.{}(), score={}, 行={}={}",
                        c.getClassName(), c.getMethodName(), c.getScore(),
                        c.getStartLine(), c.getEndLine());
            }
        }

        try {
            return objectMapper.writeValueAsString(Map.of(
                    "queryLength", codeSnippet.length(),
                    "resultCount", samples.size(),
                    "samples", samples
            ));
        } catch (Exception e) {
            log.error("[SimilarCodeTool] 序列化失败", e);
            return "{\"error\": \"序列化检索结果失败\"}";
        }
    }
}
