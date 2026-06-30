package com.codereview.agent.ast.model;

/**
 * 问题严重度枚举（参考 SonarQube 分级）
 * 用于标识 AST 检测到的问题的严重程度，影响后续展示顺序和处理优先级。
 */
public enum Severity {

    /** 阻断级：必须立即修复，否则不能上线（如死锁风险、SQL 注入） */
    BLOCKER,

    /** 严重级：可能导致系统故障（如资源泄漏、空指针风险） */
    CRITICAL,

    /** 主要级：影响代码质量或可维护性（如空 catch、方法过长） */
    MAJOR,

    /** 次要级：风格问题或轻微优化点（如魔法数字、未使用变量） */
    MINOR,

    /** 提示级：建议性改进（如命名规范、注释完善） */
    INFO;

    /**
     * 获取严重度权重（用于排序，BLOCKER 最前）
     */
    public int getWeight() {
        return BLOCKER.ordinal() - ordinal();
    }
}