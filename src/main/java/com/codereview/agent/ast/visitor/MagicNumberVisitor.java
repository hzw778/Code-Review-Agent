package com.codereview.agent.ast.visitor;

import com.codereview.agent.ast.model.RuleResult;
import com.codereview.agent.ast.model.RuleType;
import com.codereview.agent.ast.model.Severity;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;

/**
 * 魔法数字检测 Visitor
 * <p>
 * 检测规则：代码中直接使用未命名的数字常量（魔法数字）。
 * 魔法数字降低代码可读性，应该提取为有意义的常量。
 * </p>
 *
 * <p>判定逻辑：</p>
 * <ul>
 *   <li>检测 IntegerLiteralExpr 节点（整数常量）</li>
 *   <li>排除常用值：0, 1, -1, 2（业务上几乎不会产生歧义）</li>
 *   <li>排除常量定义：{@code static final int MAX = 100;} 中的 100 不算魔法数字</li>
 *   <li>排除注解参数：{@code @RequestMapping(value = 100)} 不算</li>
 * </ul>
 */
@Slf4j
public class MagicNumberVisitor extends VoidVisitorAdapter<List<RuleResult>> {

    /** 不视为魔法数字的常用值（业务上几乎不会产生歧义） */
    private static final Set<String> ALLOWED_VALUES = Set.of("0", "1", "2");

    @Override
    public void visit(IntegerLiteralExpr expr, List<RuleResult> results) {
        String value = expr.getValue();

        // 排除常用值
        if (!ALLOWED_VALUES.contains(value)) {
            // 检查父节点：是否在常量定义中（static final 字段初始化）
            // 如果父节点是 FieldDeclaration 且是 static final，跳过
            if (!isInConstantDefinition(expr)) {
                int line = expr.getBegin().get().line;

                RuleResult result = RuleResult.builder()
                        .line(line)
                        .ruleType(RuleType.MAGIC_NUMBER)
                        .severity(Severity.MINOR)
                        .message(String.format("魔法数字：%s（建议提取为有意义的常量）", value))
                        .suggestion("提取为常量：private static final int XXX = " + value + ";")
                        .build();
                results.add(result);

                log.debug("[AST规则] 检测到魔法数字, 值={}, 行号={}", value, line);
            }
        }

        // 不需要继续遍历子节点（IntegerLiteralExpr 是叶子节点，没有子节点）
        // 但保险起见还是调用 super.visit
        super.visit(expr, results);
    }

    /**
     * 判断字面量是否在常量定义中
     * 即：是否在 static final 字段的初始化表达式中
     */
    private boolean isInConstantDefinition(IntegerLiteralExpr expr) {
        // javaparser 3.25.x：Node 没有 findParent 方法，用 findAncestor 替代（功能等价，向上找祖先节点）
        return expr.findAncestor(com.github.javaparser.ast.body.FieldDeclaration.class)
                .map(field -> field.isStatic() && field.isFinal())
                .orElse(false);
    }
}
