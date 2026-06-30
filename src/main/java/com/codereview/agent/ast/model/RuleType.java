package com.codereview.agent.ast.model;

/**
 * AST 检测规则类型枚举
 * 每个枚举值对应一条具体的检测规则，关联到对应的 Visitor 实现。
 * 后续 RAG 知识库可以基于 ruleType 召回对应的规范条目。
 */
public enum RuleType {

    /** 空 catch 块：catch 后方法体为空，吞掉异常 */
    EMPTY_CATCH("空的 catch 块"),

    /** 方法过长：方法体超过阈值（默认 30 行） */
    METHOD_TOO_LONG("方法过长"),

    /** 魔法数字：代码中直接使用未命名的数字常量 */
    MAGIC_NUMBER("魔法数字"),

    /** 资源泄漏：未关闭的 Closeable 资源（如 InputStream、Connection） */
    RESOURCE_LEAK("资源泄漏"),

    /** 命名规范：类名/方法名/变量名不符合驼峰规范 */
    NAMING_CONVENTION("命名规范");

    /** 规则中文名称（用于日志和报告展示） */
    private final String displayName;

    RuleType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}