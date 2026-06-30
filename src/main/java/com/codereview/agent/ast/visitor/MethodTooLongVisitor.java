package com.codereview.agent.ast.visitor;

import com.codereview.agent.ast.model.RuleResult;
import com.codereview.agent.ast.model.RuleType;
import com.codereview.agent.ast.model.Severity;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 方法过长检测 Visitor
 * <p>
 * 检测规则：方法体语句数超过阈值（默认 30 行）。
 * 方法过长通常意味着职责不单一，难以维护和测试。
 * </p>
 *
 * <p>判定逻辑：</p>
 * <ul>
 *   <li>统计方法体内所有顶层语句数量（不含方法签名、注解）</li>
 *   <li>超过 {@link #MAX_LINES} 阈值则报告</li>
 *   <li>不计嵌套语句内部的行数（避免嵌套越深越不准）</li>
 * </ul>
 */
@Slf4j
public class MethodTooLongVisitor extends VoidVisitorAdapter<List<RuleResult>> {

    /** 方法体语句数阈值（超过此值视为过长） */
    private static final int MAX_LINES = 30;

    @Override
    public void visit(MethodDeclaration method, List<RuleResult> results) {
        // 方法体存在时才检测（抽象方法和接口方法没有方法体）
        method.getBody().ifPresent(body -> {
            // 统计方法体顶层语句数（statements 是直接子语句列表）
            // 不递归统计嵌套语句内部，避免嵌套深的简单方法被误报
            int statementCount = body.getStatements().size();

            if (statementCount > MAX_LINES) {
                int line = method.getBegin().get().line;
                String methodName = method.getNameAsString();

                RuleResult result = RuleResult.builder()
                        .line(line)
                        .ruleType(RuleType.METHOD_TOO_LONG)
                        .severity(Severity.MAJOR)
                        .message(String.format("方法 %s 过长：%d 条语句（阈值 %d）", methodName, statementCount, MAX_LINES))
                        .suggestion("考虑将方法拆分为多个职责单一的小方法，每个方法不超过 30 行")
                        .build();
                results.add(result);

                log.debug("[AST规则] 检测到方法过长, 方法名={}, 语句数={}, 行号={}",
                        methodName, statementCount, line);
            }
        });

        // 继续遍历子节点（方法内部可能还有内部类的方法需要检测）
        super.visit(method, results);
    }
}
