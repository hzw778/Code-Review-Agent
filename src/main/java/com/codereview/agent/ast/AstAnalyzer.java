package com.codereview.agent.ast;

import com.codereview.agent.ast.model.RuleResult;
import com.codereview.agent.ast.visitor.*;
import com.codereview.agent.exception.BusinessException;
import com.codereview.agent.exception.ErrorCode;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * AST 静态分析器
 * <p>
 * 核心职责：把 Java 文件解析成 AST，跑所有注册的 Visitor，收集检测结果。
 * </p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>所有 Visitor 继承 VoidVisitorAdapter&lt;List&lt;RuleResult&gt;&gt;，可用父类型统一管理</li>
 *   <li>Visitor 不感知文件路径，由本类跑完后统一补 file 字段</li>
 *   <li>新增规则只需 new 一个 Visitor 加到 VISITORS 列表，符合开闭原则</li>
 * </ul>
 */
@Slf4j
@Service
public class AstAnalyzer {

    /**
     * 所有注册的 Visitor（无状态，可复用）
     * 新增规则只需在这里 new 一个 Visitor 即可
     */
    private final List<VoidVisitorAdapter<List<RuleResult>>> visitors = List.of(
            new EmptyCatchVisitor(),
            new MethodTooLongVisitor(),
            new MagicNumberVisitor(),
            new ResourceLeakVisitor(),
            new NamingConventionVisitor()
    );

    /**
     * 分析单个 Java 文件
     *
     * @param javaFile Java 文件对象
     * @return 检测到的问题列表（空列表表示无问题）
     */
    public List<RuleResult> analyze(File javaFile) {
        String filePath = javaFile.getAbsolutePath();
        log.info("[AST分析] 开始分析文件: {}", filePath);

        long startTime = System.currentTimeMillis();
        List<RuleResult> results = new ArrayList<>();

        try {
            // 1. 解析：源码文本 → CompilationUnit（AST 根节点）
            CompilationUnit cu = StaticJavaParser.parse(javaFile);

            // 2. 依次跑所有 Visitor，每个 Visitor 都遍历一遍 AST
            //    cu.accept(visitor, results) 会触发遍历，JavaParser 自动回调 visit 方法
            for (VoidVisitorAdapter<List<RuleResult>> visitor : visitors) {
                cu.accept(visitor, results);
            }

            // 3. 统一给所有结果补 file 字段（Visitor 不感知路径，由本类统一填）
            String finalFilePath = filePath;
            results.forEach(r -> r.setFile(finalFilePath));

            long costMs = System.currentTimeMillis() - startTime;
            log.info("[AST分析] 分析完成, 文件={}, 问题数={}, 耗时={}ms",
                    filePath, results.size(), costMs);
            return results;

        } catch (Exception e) {
            // 解析失败：可能是文件读取失败或 Java 语法错误
            log.error("[异常] AST 分析失败, 文件={}, 错误={}", filePath, e.getMessage(), e);
            throw new BusinessException(ErrorCode.AST_PARSE_FAILED,
                    "AST 解析失败: " + e.getMessage());
        }
    }
}