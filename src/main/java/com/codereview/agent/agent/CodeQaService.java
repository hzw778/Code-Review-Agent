package com.codereview.agent.agent;

import com.codereview.agent.rag.model.CodeSearchResult;
import com.codereview.agent.rag.model.RuleSearchResult;
import com.codereview.agent.tool.RagSearchTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码问答服务（CODE_QA 分支）
 *
 * <p>从 ChatService 拆出，专门负责 CODE_QA 类型的处理：
 * <ol>
 *   <li>RAG 检索（规则 + 代码双路）</li>
 *   <li>构造 references（含 lineNumbers，前端可点击跳转到源码行）</li>
 *   <li>拼装 systemPrompt（规则条目 + 代码示例注入）</li>
 * </ol>
 *
 * <p>拆分动机：ChatService 同时承担 Router 编排 + 三分支处理 + LLM 流式 + 护栏，
 * CODE_QA 分支的 RAG 逻辑较重（30+ 行），抽到独立服务后：
 * <ul>
 *   <li>ChatService 聚焦"编排"（Router 分流 + LLM 流式 + 护栏）</li>
 *   <li>CodeQaService 聚焦"检索增强"（RAG 检索 + prompt 构造）</li>
 *   <li>单一职责，便于后续扩展（如换检索策略、加 rerank、query 改写）</li>
 * </ul>
 *
 * <p>Agent 设计经验——编排服务和能力服务的分离：
 * 编排服务（ChatService）决定"调谁、什么顺序、怎么流式"，不关心具体能力实现；
 * 能力服务（CodeQaService）决定"怎么做 RAG、怎么拼 prompt"，不关心结果怎么流给前端。
 * 这种分离让 Agent 的"决策"和"执行"解耦，符合 ReAct 模式里"Thought 编排 / Action 执行"的分工。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeQaService {

    private final RagSearchTool ragSearchTool;

    /** CODE_QA 系统提示模板（规则 + 代码示例注入） */
    private static final String QA_SYSTEM = """
            你是 Java 代码规范助手。请基于下方检索到的【规范条目】和【代码示例】回答用户问题。
            如果检索结果为空，请基于你自己的知识回答，并标注"未检索到相关规范"。
            回答要简洁、可操作，必要时引用规范条目名称和代码行号（如"第 42-58 行"）。

            【规范条目】
            %s

            【代码示例】
            %s
            """;

    /** CODE_QA 处理结果（不可变） */
    public record QaContext(String systemPrompt, List<Map<String, Object>> references, Map<String, Object> trace) {}

    /**
     * 处理 CODE_QA 请求：RAG 检索 + 拼 prompt + 构造 references。
     *
     * @param userInput 用户问题
     * @return QaContext（systemPrompt 给 LLM，references 给前端渲染引用列表，trace 给数据传输面板）
     */
    public QaContext handle(String userInput) {
        long t1 = System.currentTimeMillis();
        RagSearchTool.SearchResult sr = ragSearchTool.searchAll(userInput);
        long ragCost = System.currentTimeMillis() - t1;

        int ruleCount = sr.getRules() != null ? sr.getRules().size() : 0;
        int codeCount = sr.getCodes() != null ? sr.getCodes().size() : 0;
        log.info("[CodeQaService] RAG 检索完成, 规则={}, 代码={}, 耗时={}ms", ruleCount, codeCount, ragCost);

        // 1. 构造 references（前端渲染引用列表 + code 类型带 lineNumbers 可点击跳转）
        List<Map<String, Object>> references = new ArrayList<>();
        if (sr.getRules() != null) {
            for (RuleSearchResult r : sr.getRules()) {
                references.add(refMap("rule", r.getRuleName(), r.getSource(), r.getScore(),
                        r.getContent(), null));
            }
        }
        if (sr.getCodes() != null) {
            for (CodeSearchResult c : sr.getCodes()) {
                // lineNumbers 用 "start-end" 格式，前端可解析后渲染成可点击链接
                String lineNumbers = c.getStartLine() + "-" + c.getEndLine();
                references.add(refMap("code",
                        c.getClassName() + "#" + c.getMethodName(),
                        c.getSource(), c.getScore(),
                        c.getSignature() != null ? c.getSignature() : c.getContent(),
                        lineNumbers));
            }
        }

        // 2. 拼 systemPrompt（规则条目 + 代码示例，含行号）
        String systemPrompt = String.format(QA_SYSTEM, formatRules(sr.getRules()), formatCodes(sr.getCodes()));

        // 3. 构造 trace（数据传输面板用）
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("stage", "RagSearchTool");
        trace.put("detail", "规则=" + ruleCount + " 代码=" + codeCount);
        trace.put("costMs", ragCost);
        trace.put("model", "text-embedding-v3 (ES KNN)");
        trace.put("query", userInput);
        trace.put("topK", 3);
        trace.put("similarityThreshold", 0.5);
        trace.put("ruleCount", ruleCount);
        trace.put("codeCount", codeCount);
        trace.put("rules", sr.getRules());
        trace.put("codes", sr.getCodes());

        return new QaContext(systemPrompt, references, trace);
    }

    /**
     * 引用项构造。
     *
     * @param kind       rule / code
     * @param title      规则名 或 类名#方法名
     * @param source     来源文件
     * @param score      相似度分数
     * @param content    内容摘要（截断到 200 字符）
     * @param lineNumbers 行号区间（仅 code 类型有，如 "42-58"），rule 类型传 null
     */
    private Map<String, Object> refMap(String kind, String title, String source, double score,
                                       String content, String lineNumbers) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("title", title);
        m.put("source", source);
        m.put("score", score);
        m.put("content", content != null && content.length() > 200 ? content.substring(0, 200) + "..." : content);
        if (lineNumbers != null) {
            m.put("lineNumbers", lineNumbers);
        }
        return m;
    }

    /** 格式化规则条目（注入 systemPrompt） */
    private String formatRules(List<RuleSearchResult> rules) {
        if (rules == null || rules.isEmpty()) return "(无)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rules.size(); i++) {
            RuleSearchResult r = rules.get(i);
            sb.append(i + 1).append(". [").append(r.getRuleName()).append("] (score=")
              .append(String.format("%.3f", r.getScore())).append(")\n")
              .append(r.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    /** 格式化代码示例（注入 systemPrompt，含行号便于 LLM 引用） */
    private String formatCodes(List<CodeSearchResult> codes) {
        if (codes == null || codes.isEmpty()) return "(无)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < codes.size(); i++) {
            CodeSearchResult c = codes.get(i);
            sb.append(i + 1).append(". ").append(c.getClassName()).append("#").append(c.getMethodName())
              .append(" (行 ").append(c.getStartLine()).append("-").append(c.getEndLine())
              .append(", score=").append(String.format("%.3f", c.getScore())).append(")\n")
              .append(c.getContent()).append("\n\n");
        }
        return sb.toString();
    }
}
