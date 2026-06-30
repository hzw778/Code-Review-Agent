package com.codereview.agent.ast.visitor;

import com.codereview.agent.ast.model.RuleResult;
import com.codereview.agent.ast.model.RuleType;
import com.codereview.agent.ast.model.Severity;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 空 catch 块检测 Visitor
 * 检测规则：catch 子句的方法体（BlockStmt）为空。
 * Visitor 只关心 AST 结构本身，不感知文件路径（路径由 AstAnalyzer 统一补充）。
 */
@Slf4j
public class EmptyCatchVisitor extends VoidVisitorAdapter<List<RuleResult>> {

    @Override
    public void visit(CatchClause catchClause, List<RuleResult> results) {
        // JavaParser 遍历到每一个 catch 子句时，都会回调这个方法

        // 判空：catch 块的方法体是否没有任何语句（注释不算语句）
        if (catchClause.getBody().isEmpty()) {
            int line = catchClause.getBegin().get().line;

            // file 字段先不填，由 AstAnalyzer 跑完后统一补
            RuleResult result = RuleResult.builder()
                    .line(line)
                    .ruleType(RuleType.EMPTY_CATCH)
                    .severity(Severity.MAJOR)
                    .message("空的 catch 块会吞掉异常，导致问题难以排查")
                    .suggestion("至少记录日志：log.error(\"操作失败\", e); 或重抛：throw new RuntimeException(e);")
                    .build();
            results.add(result);

            log.debug("[AST规则] 检测到空 catch 块, 行号={}", line);
        }

        // 必须调用 super.visit，继续遍历子节点
        super.visit(catchClause, results);
    }
}