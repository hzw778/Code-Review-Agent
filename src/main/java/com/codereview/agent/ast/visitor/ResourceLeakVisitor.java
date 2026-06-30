package com.codereview.agent.ast.visitor;

import com.codereview.agent.ast.model.RuleResult;
import com.codereview.agent.ast.model.RuleType;
import com.codereview.agent.ast.model.Severity;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;

/**
 * 资源泄漏检测 Visitor（简化版）
 * <p>
 * 检测规则：直接 new 出 IO/数据库资源（如 FileInputStream、Connection）但未使用
 * try-with-resources 自动关闭，可能导致资源泄漏。
 * </p>
 *
 * <p>简化版判定逻辑：</p>
 * <ul>
 *   <li>检测 VariableDeclarator 的初始化表达式是 new XxxStream() / new XxxConnection()</li>
 *   <li>检查所在语句是否在 try-with-resources 的 try 块中</li>
 *   <li>如果在普通 try 块或方法体内，报告资源泄漏风险</li>
 * </ul>
 *
 * <p>局限性（后续优化）：</p>
 * <ul>
 *   <li>不能识别自定义类型是否实现 Closeable 接口（需要符号解析）</li>
 *   <li>不能识别 finally 块中的手动 close() 调用</li>
 *   <li>仅检测变量声明中的 new，不检测方法参数返回值等场景</li>
 * </ul>
 */
@Slf4j
public class ResourceLeakVisitor extends VoidVisitorAdapter<List<RuleResult>> {

    /** 可能产生资源泄漏的类型后缀（简化版用名称匹配） */
    private static final Set<String> LEAK_PRONE_SUFFIXES = Set.of(
            "InputStream", "OutputStream", "Reader", "Writer",
            "Connection", "Statement", "PreparedStatement", "ResultSet",
            "BufferedReader", "BufferedWriter", "PrintStream", "PrintWriter"
    );

    @Override
    public void visit(VariableDeclarator variable, List<RuleResult> results) {
        // 只检测有初始化表达式的变量（即 = new Xxx() 这种）
        variable.getInitializer().ifPresent(initializer -> {
            // 只处理 new XXX() 这种对象创建
            if (initializer instanceof ObjectCreationExpr) {
                ObjectCreationExpr newExpr = (ObjectCreationExpr) initializer;
                String typeName = newExpr.getType().getNameAsString();

                // 检查是否是可能泄漏的类型
                if (isLeakProneType(typeName)) {
                    // 检查是否在 try-with-resources 中
                    if (!isInTryWithResources(variable)) {
                        int line = variable.getBegin().get().line;

                        RuleResult result = RuleResult.builder()
                                .line(line)
                                .ruleType(RuleType.RESOURCE_LEAK)
                                .severity(Severity.CRITICAL)
                                .message(String.format("资源 %s 未使用 try-with-resources，可能泄漏", typeName))
                                .suggestion("改用 try-with-resources 自动关闭：try (" + typeName + " r = new " + typeName + "(...)) { ... }")
                                .build();
                        results.add(result);

                        log.debug("[AST规则] 检测到资源泄漏风险, 类型={}, 行号={}", typeName, line);
                    }
                }
            }
        });

        super.visit(variable, results);
    }

    /**
     * 判断类型是否是可能泄漏的类型（简化版用名称后缀匹配）
     */
    private boolean isLeakProneType(String typeName) {
        return LEAK_PRONE_SUFFIXES.stream()
                .anyMatch(suffix -> typeName.endsWith(suffix));
    }

    /**
     * 判断变量是否在 try-with-resources 中
     * <p>
     * AST 结构：try (InputStream is = new FileInputStream(...)) {} 中
     * - TryStmt.getResources() 返回 NodeList&lt;Expression&gt;
     * - 每个 Expression 实际是 VariableDeclarationExpr（变量声明表达式）
     * - VariableDeclarationExpr.getVariables() 返回 NodeList&lt;VariableDeclarator&gt;
     * - VariableDeclarator 才是我们要找的 variable
     * </p>
     */
    private boolean isInTryWithResources(VariableDeclarator variable) {
        // 直接看 variable 的父节点是不是 VariableDeclarationExpr
        // VariableDeclarationExpr 的父节点是不是 TryStmt
        // 如果是，说明 variable 在 try (...) 的资源声明中
        return variable.getParentNode()
                .filter(parent -> parent instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr)
                .flatMap(parent -> parent.getParentNode())
                .filter(grandParent -> grandParent instanceof com.github.javaparser.ast.stmt.TryStmt)
                .isPresent();
    }
}
