package com.codereview.agent.guardrail;

/**
 * 护栏检测结果
 *
 * @param blocked    是否拦截（true 表示命中恶意模式，应拒绝处理）
 * @param reason     拦截原因（命中哪条规则，供日志和前端展示）
 * @param sanitized  输出护栏用：脱敏后的文本（输入护栏此字段为 null）
 */
public record GuardrailResult(boolean blocked, String reason, String sanitized) {

    /** 放行 */
    public static GuardrailResult pass() {
        return new GuardrailResult(false, null, null);
    }

    /** 拦截 */
    public static GuardrailResult block(String reason) {
        return new GuardrailResult(true, reason, null);
    }

    /** 输出脱敏结果 */
    public static GuardrailResult sanitized(String text) {
        return new GuardrailResult(false, null, text);
    }
}
