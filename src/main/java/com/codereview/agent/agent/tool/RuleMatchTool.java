package com.codereview.agent.agent.tool;

import com.codereview.agent.ast.model.RuleType;
import com.codereview.agent.rag.model.RuleSearchResult;
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
 * 规则精确匹配工具：基于 AST 检测到的 ruleType 批量查询规范条目。
 *
 * <p>与 RagSearchTool 的区别：
 * <ul>
 *   <li>RagSearchTool：单条语义检索，query 是自然语言（模糊）</li>
 *   <li>RuleMatchTool：批量结构化匹配，ruleTypes 是枚举值（精确），一次拿多个问题的规范依据</li>
 * </ul>
 *
 * <p>设计动机：
 * <ol>
 *   <li><b>批量减少 LLM 调用</b>：AST 一次检测出 N 个问题，用 RagSearchTool 要 LLM 逐个调 N 次
 *       （N 轮 ReAct 循环），RuleMatchTool 一次调用批量返回 N 个规范依据，省 N-1 轮 LLM 调用</li>
 *   <li><b>查询增强提升准确率</b>：用 RuleType.displayName（如"空的 catch 块"）构造增强 query
 *       （"阿里巴巴 Java 开发规范 空的 catch 块"），比 LLM 自由发挥的自然语言 query 更精准</li>
 *   <li><b>topK=1 去噪</b>：ruleType 是确定的问题分类，只需最匹配的那条规范，
 *       不需要 topK=3 召回多条（避免噪声干扰 LLM 判断）</li>
 * </ol>
 *
 * <p>Agent 工具设计经验——批量工具的价值：
 * Agent 每轮只能调一个工具（ReAct 约定）。如果任务需要 N 次同类操作（如查 N 个问题的规范），
 * 逐个调要 N 轮循环（N 次 LLM 推理 + N 次工具执行）。批量工具把 N 次合并为 1 次，
 * 大幅减少 ReAct 轮次——这在 LLM 调用昂贵、延迟敏感的场景价值巨大。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleMatchTool implements AgentTool {

    private final RagSearchTool ragSearchTool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 查询增强前缀（让向量检索更偏向规范文档） */
    private static final String QUERY_PREFIX = "阿里巴巴 Java 开发规范 ";

    /** 批量匹配时每个 ruleType 只取最匹配的 1 条（避免噪声） */
    private static final int MATCH_TOP_K = 1;

    /** 批量匹配的相似度阈值（略低于默认 0.5，因为查询增强后的 query 可能与规范文本相似度偏低） */
    private static final double MATCH_THRESHOLD = 0.3;

    @Override
    public String getName() {
        return "RuleMatchTool";
    }

    @Override
    public String getDescription() {
        return "基于 AST 检测到的规则类型（ruleType）批量查询对应规范条目。"
                + "当 AstAnalysisTool 返回多个问题需要一次性获取所有规范依据时使用此工具，"
                + "比逐个调 RagSearchTool 更高效。";
    }

    @Override
    public String getParametersDescription() {
        return "{\"ruleTypes\": [\"规则类型数组（必填，从 AstAnalysisTool 返回的 issues[].ruleType 中提取，"
                + "如 [\\\"EMPTY_CATCH\\\", \\\"MAGIC_NUMBER\\\"]\")]}";
    }

    @Override
    public String execute(Map<String, Object> args) {
        Object ruleTypesObj = args.get("ruleTypes");
        log.info("[RuleMatchTool] 批量匹配规范, ruleTypes={}", ruleTypesObj);

        if (ruleTypesObj == null) {
            return "{\"error\": \"ruleTypes 不能为空\"}";
        }

        List<String> ruleTypeStrList = toStringList(ruleTypesObj);
        if (ruleTypeStrList.isEmpty()) {
            return "{\"error\": \"ruleTypes 为空\"}";
        }

        // 解析 ruleType 字符串为枚举（容错：无效值跳过不报错）
        List<RuleType> ruleTypes = new ArrayList<>();
        for (String s : ruleTypeStrList) {
            try {
                ruleTypes.add(RuleType.valueOf(s.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("[RuleMatchTool] 未知 ruleType, 跳过: {}", s);
            }
        }

        if (ruleTypes.isEmpty()) {
            return "{\"matchedCount\": 0, \"matches\": [], "
                    + "\"message\": \"没有有效的 ruleType（可用值: EMPTY_CATCH, METHOD_TOO_LONG, "
                    + "MAGIC_NUMBER, RESOURCE_LEAK, NAMING_CONVENTION)\"}";
        }

        // 批量查询：对每个 ruleType 构造增强 query 调向量检索
        List<Map<String, Object>> matches = new ArrayList<>();
        for (RuleType rt : ruleTypes) {
            String enhancedQuery = QUERY_PREFIX + rt.getDisplayName();
            List<RuleSearchResult> results = ragSearchTool.searchRules(enhancedQuery);

            Map<String, Object> match = new LinkedHashMap<>();
            match.put("ruleType", rt.name());
            match.put("displayName", rt.getDisplayName());
            if (results != null && !results.isEmpty()) {
                // topK=1，取最匹配的
                RuleSearchResult best = results.get(0);
                match.put("rule", Map.of(
                        "ruleName", best.getRuleName(),
                        "category", best.getCategory() != null ? best.getCategory() : "",
                        "content", best.getContent(),
                        "source", best.getSource() != null ? best.getSource() : "",
                        "score", best.getScore()
                ));
                log.info("[RuleMatchTool] 匹配成功, ruleType={}, ruleName={}, score={}",
                        rt.name(), best.getRuleName(), best.getScore());
            } else {
                match.put("rule", null);
                match.put("message", "未匹配到规范条目");
                log.warn("[RuleMatchTool] 未匹配到规范, ruleType={}, query={}", rt.name(), enhancedQuery);
            }
            matches.add(match);
        }

        try {
            return objectMapper.writeValueAsString(Map.of(
                    "matchedCount", matches.size(),
                    "matches", matches
            ));
        } catch (Exception e) {
            log.error("[RuleMatchTool] 序列化失败", e);
            return "{\"error\": \"序列化匹配结果失败\"}";
        }
    }

    /**
     * 把 LLM 传入的参数转为 List<String>（兼容数组和逗号分隔字符串）。
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
