package com.codereview.agent.guardrail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输出护栏：对 LLM 输出做敏感信息脱敏
 *
 * <p>LLM 在生成代码示例、解释配置时可能"幻觉"出真实凭证格式
 * （尤其当 RAG 检索到的代码片段里含密钥时），输出护栏在内容流到前端前做正则替换。
 *
 * <p>脱敏策略：保留前 4 + 后 2 字符便于识别类型，中间替换为 ***。
 * 例：sk-abcd1234567890wxyz → sk-ab***yz
 *
 * <p>与输入护栏（PromptInjectionDetector）的区别：
 * <ul>
 *   <li>输入护栏：block 拦截，不送 LLM</li>
 *   <li>输出护栏：sanitized 替换，仍返回内容（只是脱敏后的版本）</li>
 * </ul>
 *
 * <p>为何输出只脱敏不拦截：用户问"什么是 API Key"是无害的，
 * LLM 回答里的示例 key 也是无害的，但完整展示会误导用户以为是真凭证。
 * 脱敏后既能教学又不误导。
 *
 * <p>设计取舍——逐 token 脱敏的局限：
 * LLM 流式输出按 token 切片，敏感字符串可能跨 token 边界（如 "sk-" + "abc123"）
 * 导致逐 token 正则匹配漏检。本项目采用"逐 token 脱敏 + 兜底整段再脱敏"双保险：
 * <ol>
 *   <li>每个 token 单独脱敏（覆盖完整 token 内的敏感串）</li>
 *   <li>DONE 事件里对累积内容再做一次脱敏（覆盖跨 token 的敏感串，但 token 已发出无法回收——
 *       所以此层只用于日志告警，实际防护以逐 token 为主）</li>
 * </ol>
 * 生产级实现会用滑动窗口缓冲（buffer recent N chars + match），这里简化处理。
 */
@Slf4j
@Component
public class OutputGuardrail {

    /**
     * 敏感信息检测规则（顺序无关，每条独立替换）。
     * 每条规则：正则 + 类型说明。
     */
    private static final List<SanitizeRule> RULES = List.of(
            // OpenAI / 兼容服务 API Key（sk- 开头）
            new SanitizeRule("sk-[A-Za-z0-9_\\-]{16,}", "API Key (OpenAI 兼容)"),
            // 阿里云 AccessKey（LTAI 开头）
            new SanitizeRule("LTAI[A-Za-z0-9]{12,}", "AccessKey (阿里云)"),
            // AWS AccessKey（AKIA 开头）
            new SanitizeRule("AKIA[0-9A-Z]{16}", "AccessKey (AWS)"),
            // Bearer Token（HTTP Authorization 头）
            new SanitizeRule("(?i)bearer\\s+[A-Za-z0-9_\\-\\.]{20,}", "Bearer Token"),
            // JWT（三段式 xxx.yyy.zzz，eyJ 开头是 base64 编码的 {"alg...）
            new SanitizeRule("eyJ[A-Za-z0-9_\\-]{10,}\\.eyJ[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}", "JWT"),
            // 私钥块（含 PEM 头尾）
            new SanitizeRule("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----",
                    "私钥 (PEM)"),
            // 通用凭证赋值：password=xxx / secret: xxx / api_key=xxx
            new SanitizeRule("(?i)(password|passwd|secret|token|api[_\\-]?key)\\s*[=:]\\s*[\"']?[A-Za-z0-9_\\-]{8,}",
                    "凭证赋值"),
            // 邮箱
            new SanitizeRule("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}", "邮箱"),
            // 中国手机号（1[3-9] 开头 + 9 位数字）
            new SanitizeRule("1[3-9]\\d{9}", "手机号"),
            // 银行卡号（16-19 位连续数字）
            new SanitizeRule("\\b\\d{16,19}\\b", "银行卡号")
    );

    /**
     * 对文本做敏感信息脱敏。
     *
     * @param text 原始文本（可能是单个 token 或整段输出）
     * @return GuardrailResult.sanitized(脱敏后文本)，从不返回 blocked
     */
    public GuardrailResult sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return GuardrailResult.sanitized(text);
        }
        String result = text;
        int hitCount = 0;
        for (SanitizeRule rule : RULES) {
            Matcher m = rule.pattern.matcher(result);
            if (m.find()) {
                String matched = m.group();
                String masked = mask(matched);
                // quoteReplacement 防止脱敏后的 *** 或 $ \ 被当成替换特殊字符
                result = m.replaceAll(Matcher.quoteReplacement(masked));
                hitCount++;
                log.warn("[OutputGuardrail] 命中敏感模式: type={}, 原长度={}, 已脱敏",
                        rule.description, matched.length());
            }
        }
        if (hitCount > 0) {
            log.info("[OutputGuardrail] 本次共脱敏 {} 处敏感信息", hitCount);
        }
        return GuardrailResult.sanitized(result);
    }

    /**
     * 脱敏算法：保留前缀便于识别类型，中间 ***。
     * <ul>
     *   <li>长度 ≤ 6：直接 ***（避免保留太少反而暴露）</li>
     *   <li>长度 7-12：保留前 2 + 后 2</li>
     *   <li>长度 &gt; 12：保留前 4 + 后 2</li>
     * </ul>
     */
    private String mask(String s) {
        if (s.length() <= 6) return "***";
        if (s.length() <= 12) return s.substring(0, 2) + "***" + s.substring(s.length() - 2);
        return s.substring(0, 4) + "***" + s.substring(s.length() - 2);
    }

    /** 单条脱敏规则 */
    private record SanitizeRule(String regex, String description, Pattern pattern) {
        SanitizeRule(String regex, String description) {
            this(regex, description, Pattern.compile(regex));
        }
    }
}
