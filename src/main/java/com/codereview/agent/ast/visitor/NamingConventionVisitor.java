package com.codereview.agent.ast.visitor;

import com.codereview.agent.ast.model.RuleResult;
import com.codereview.agent.ast.model.RuleType;
import com.codereview.agent.ast.model.Severity;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 命名规范检测 Visitor
 * <p>
 * 检测规则：类名/方法名/变量名不符合驼峰命名规范。
 * </p>
 *
 * <p>判定规则（参考阿里巴巴 Java 开发手册）：</p>
 * <ul>
 *   <li>类名：UpperCamelCase（首字母大写，如 UserService、OrderController）</li>
 *   <li>方法名：lowerCamelCase（首字母小写，如 getUserById、calculateTotal）</li>
 *   <li>变量名：lowerCamelCase（首字母小写，如 userName、orderList）</li>
 *   <li>常量：UPPER_SNAKE_CASE（全大写+下划线，如 MAX_RETRY_COUNT）</li>
 * </ul>
 */
@Slf4j
public class NamingConventionVisitor extends VoidVisitorAdapter<List<RuleResult>> {

    /** 类名规范：UpperCamelCase（首字母大写） */
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile("^[A-Z][a-zA-Z0-9]*$");

    /** 方法名规范：lowerCamelCase（首字母小写） */
    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("^[a-z][a-zA-Z0-9]*$");

    /** 变量名规范：lowerCamelCase（首字母小写） */
    private static final Pattern VARIABLE_NAME_PATTERN = Pattern.compile("^[a-z][a-zA-Z0-9]*$");

    @Override
    public void visit(ClassOrInterfaceDeclaration clazz, List<RuleResult> results) {
        String name = clazz.getNameAsString();
        if (!CLASS_NAME_PATTERN.matcher(name).matches()) {
            int line = clazz.getBegin().get().line;
            results.add(RuleResult.builder()
                    .line(line)
                    .ruleType(RuleType.NAMING_CONVENTION)
                    .severity(Severity.INFO)
                    .message(String.format("类名 %s 不符合 UpperCamelCase 规范", name))
                    .suggestion("类名首字母应大写，如：UserService、OrderController")
                    .build());
            log.debug("[AST规则] 类名不规范, name={}, 行号={}", name, line);
        }
        super.visit(clazz, results);
    }

    @Override
    public void visit(MethodDeclaration method, List<RuleResult> results) {
        String name = method.getNameAsString();
        if (!METHOD_NAME_PATTERN.matcher(name).matches()) {
            int line = method.getBegin().get().line;
            results.add(RuleResult.builder()
                    .line(line)
                    .ruleType(RuleType.NAMING_CONVENTION)
                    .severity(Severity.INFO)
                    .message(String.format("方法名 %s 不符合 lowerCamelCase 规范", name))
                    .suggestion("方法名首字母应小写，如：getUserById、calculateTotal")
                    .build());
            log.debug("[AST规则] 方法名不规范, name={}, 行号={}", name, line);
        }
        super.visit(method, results);
    }

    @Override
    public void visit(VariableDeclarator variable, List<RuleResult> results) {
        String name = variable.getNameAsString();
        // 排除常量（常量用 UPPER_SNAKE_CASE，单独的规则）
        // javaparser 3.25.x：Node 没有 findParent 方法，用 findAncestor 替代
        boolean isConstant = variable.findAncestor(com.github.javaparser.ast.body.FieldDeclaration.class)
                .map(field -> field.isStatic() && field.isFinal())
                .orElse(false);
        if (isConstant) {
            super.visit(variable, results);
            return;
        }

        if (!VARIABLE_NAME_PATTERN.matcher(name).matches()) {
            int line = variable.getBegin().get().line;
            results.add(RuleResult.builder()
                    .line(line)
                    .ruleType(RuleType.NAMING_CONVENTION)
                    .severity(Severity.INFO)
                    .message(String.format("变量名 %s 不符合 lowerCamelCase 规范", name))
                    .suggestion("变量名首字母应小写，如：userName、orderList")
                    .build());
            log.debug("[AST规则] 变量名不规范, name={}, 行号={}", name, line);
        }
        super.visit(variable, results);
    }
}