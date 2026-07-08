package com.codereview.agent.guardrail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt 注入检测器（输入护栏）
 *
 * <p>在用户输入进入 LLM 之前检测恶意 prompt 注入，命中则拦截不送 LLM。
 *
 * <p>检测策略：基于规则的正则匹配（而非 LLM 检测 LLM）。
 * 规则匹配快、零额外 token 成本，对常见注入模式覆盖率高。
 * 复杂语义注入仍可能漏检，但作为第一道防线足够。
 *
 * <p>典型注入模式：
 * <ul>
 *   <li>指令覆盖："ignore previous instructions"、"disregard the above"</li>
 *   <li>角色劫持："you are now a ..."、"act as ..."</li>
 *   <li>角色标签注入：&lt;system&gt;、&lt;/assistant&gt; 等伪标签</li>
 *   <li>指令窃取："show me your system prompt"、"reveal your instructions"</li>
 *   <li>越狱关键词：jailbreak、DAN</li>
 * </ul>
 *
 * <p>Agent 安全设计经验：输入护栏是"默认拒绝"原则的落地——
 * 宁可误拦一些边界输入（用户可重新表述），也不让恶意指令直达 LLM。
 * 拦截后返回明确原因，让用户知道为什么被拒，而非黑盒失败。
 */
@Slf4j
@Component
public class PromptInjectionDetector {

    /**
     * 注入检测规则（按命中优先级排序）。
     * 每条规则：正则 + 命中说明。
     */
    private static final List<Rule> RULES = List.of(
            new Rule("(?i)(ignore|disregard|forget)\\s+(all\\s+)?(the\\s+)?(previous|prior|above|earlier)\\s+(instructions?|prompts?|rules?|messages?)",
                    "指令覆盖：试图让模型忽略之前的指令"),
            new Rule("(?i)(ignore|disregard|forget)\\s+(everything|all|above)",
                    "指令覆盖：试图让模型遗忘上下文"),
            new Rule("(?i)you\\s+are\\s+(now\\s+)?(a|an)\\s+\\w+.*(instead|now|from now on)",
                    "角色劫持：试图重新定义模型角色"),
            new Rule("(?i)(act|pretend|simulate)\\s+(as|to be)\\s+(a|an)?\\s*\\w+.*(no|without)\\s+(restrictions?|rules?|limits?)",
                    "角色劫持：诱导无限制角色扮演"),
            new Rule("(?i)<\\s*/?\\s*(system|assistant|developer|tool)\\s*>",
                    "角色标签注入：伪造对话角色标签"),
            new Rule("(?i)(show|reveal|print|repeat|leak)\\s+(me\\s+)?(your|the)\\s+(system\\s+)?(prompt|instructions?|rules?|initial)",
                    "指令窃取：试图获取系统提示词"),
            new Rule("(?i)\\b(jailbreak|DAN|do anything now|developer mode|maintenance mode)\\b",
                    "越狱关键词：已知越狱模式"),
            new Rule("(?i)new\\s+(instructions?|rules?)\\s*:",
                    "指令覆盖：试图注入新指令"),
            new Rule("(?i)(reveal|expose|output)\\s+(your|the)\\s+(api\\s+key|secret|password|token)",
                    "凭证窃取：试图套取密钥")
    );

    /**
     * 检测用户输入是否含 prompt 注入。
     *
     * @param input 用户输入
     * @return 检测结果，blocked=true 表示应拦截
     */
    public GuardrailResult detect(String input) {
        if (input == null || input.isBlank()) {
            return GuardrailResult.pass();
        }
        for (Rule rule : RULES) {
            if (rule.pattern.matcher(input).find()) {
                log.warn("[PromptInjectionDetector] 命中注入规则: input={}, reason={}",
                        truncate(input, 80), rule.description);
                return GuardrailResult.block(rule.description);
            }
        }
        return GuardrailResult.pass();
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 单条检测规则 */
    private record Rule(String regex, String description, Pattern pattern) {
        Rule(String regex, String description) {
            this(regex, description, Pattern.compile(regex));
        }
    }
}
