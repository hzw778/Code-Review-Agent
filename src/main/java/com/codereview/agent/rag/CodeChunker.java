package com.codereview.agent.rag;

import com.codereview.agent.rag.model.CodeChunk;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 代码分块器：按方法切分 Java 源码。
 *
 * <p>分块策略：每个 MethodDeclaration 构成一个 chunk。
 * 方法是代码的最小可复用单元，语义最聚焦，适合作为代码 RAG 的检索单元。
 *
 * <p>与 RuleChunker（按 Markdown 标题切规范文档）形成对称设计，
 * 共同支撑 Agent 的双知识库架构。
 */
@Slf4j
@Component
public class CodeChunker {

    private final JavaParser javaParser;

    public CodeChunker() {
        this.javaParser = new JavaParser();
    }

    /**
     * 对 Java 文件按方法分块。
     *
     * @param javaFile   Java 文件路径
     * @param sourceName 来源标识（文件名）
     * @return chunk 列表
     */
    public List<CodeChunk> chunk(Path javaFile, String sourceName) {
        log.info("[代码分块] 开始分块, 文件={}, source={}", javaFile, sourceName);
        long start = System.currentTimeMillis();

        String code;
        try {
            code = Files.readString(javaFile);
        } catch (IOException e) {
            log.error("[异常] 读取代码文件失败, 文件={}", javaFile, e);
            throw new RuntimeException("读取代码文件失败: " + javaFile, e);
        }

        List<CodeChunk> chunks = doChunk(code, sourceName);

        log.info("[代码分块] 分块完成, 文件={}, chunk数={}, 耗时={}ms",
                sourceName, chunks.size(), System.currentTimeMillis() - start);
        return chunks;
    }

    /**
     * 核心分块逻辑：解析 AST，遍历所有方法声明。
     */
    private List<CodeChunk> doChunk(String code, String sourceName) {
        List<CodeChunk> chunks = new ArrayList<>();

        CompilationUnit cu = javaParser.parse(code).getResult().orElse(null);
        if (cu == null) {
            log.warn("[代码分块] 解析失败, source={}", sourceName);
            return chunks;
        }

        // findAll 递归查找所有方法声明（含内部类的方法）
        List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);
        int index = 0;

        for (MethodDeclaration method : methods) {
            // 跳过抽象方法（无方法体，无法作为代码示例）
            if (!method.getBody().isPresent()) {
                continue;
            }

            String className = method.findAncestor(ClassOrInterfaceDeclaration.class)
                    .map(ClassOrInterfaceDeclaration::getNameAsString)
                    .orElse("Unknown");

            String methodName = method.getNameAsString();
            // getDeclarationAsString(modifiers, throws, paramNames)
            String signature = method.getDeclarationAsString(true, false, true);
            String content = method.toString();
            int startLine = method.getRange().map(r -> r.begin.line).orElse(0);
            int endLine = method.getRange().map(r -> r.end.line).orElse(0);

            log.debug("[代码分块] chunk#{} {}.{}() 行号={}-{}",
                    index, className, methodName, startLine, endLine);

            chunks.add(CodeChunk.builder()
                    .content(content)
                    .source(sourceName)
                    .className(className)
                    .methodName(methodName)
                    .signature(signature)
                    .startLine(startLine)
                    .endLine(endLine)
                    .index(index++)
                    .build());
        }

        return chunks;
    }
}
