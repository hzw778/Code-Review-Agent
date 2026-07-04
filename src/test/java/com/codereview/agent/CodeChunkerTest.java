package com.codereview.agent;

import com.codereview.agent.rag.CodeChunker;
import com.codereview.agent.rag.model.CodeChunk;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeChunkerTest {

    @Test
    void chunk_shouldSplitByMethod() {
        CodeChunker chunker = new CodeChunker();
        Path path = Paths.get("src/test/sample/GoodExample.java");

        List<CodeChunk> chunks = chunker.chunk(path, "GoodExample.java");

        // GoodExample 有 3 个方法（findUser, processData, logError）
        assertEquals(3, chunks.size());

        // 验证第一个方法
        CodeChunk first = chunks.get(0);
        assertEquals("GoodExample", first.getClassName());
        assertEquals("findUser", first.getMethodName());
        assertEquals("GoodExample.java", first.getSource());
        assertTrue(first.getSignature().contains("findUser"));
        assertTrue(first.getSignature().contains("String"));
        assertTrue(first.getContent().contains("return"));
        assertTrue(first.getStartLine() < first.getEndLine());

        System.out.println("===== 代码分块结果 =====");
        chunks.forEach(c -> System.out.printf(
                "chunk#%d %s.%s() 行号=%d-%d 长度=%d%n",
                c.getIndex(), c.getClassName(), c.getMethodName(),
                c.getStartLine(), c.getEndLine(), c.getContent().length()));
    }
}
