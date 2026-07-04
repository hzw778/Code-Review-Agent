package com.codereview.agent;

import com.codereview.agent.rag.model.RuleChunk;
import com.codereview.agent.rag.RuleChunker;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuleChunkerTest {

    @Test
    void chunk_alibabaStyle_shouldReturnCorrectChunks() {
        RuleChunker chunker = new RuleChunker();
        Path path = Paths.get("src/main/resources/knowledge/alibaba-java-style.md");

        List<RuleChunk> chunks = chunker.chunk(path, "alibaba-java-style.md");

        // alibaba-java-style.md 有 20 个二级标题（##），每个 ## 一个 chunk
        assertEquals(20, chunks.size());

        RuleChunk first = chunks.get(0);
        assertEquals("命名规范", first.getCategory());
        assertEquals("类名使用 UpperCamelCase", first.getRuleName());
        assertEquals("alibaba-java-style.md", first.getSource());
        assertTrue(first.getContent().contains("## 类名使用 UpperCamelCase"));

        System.out.println("===== 分块结果预览 =====");
        chunks.forEach(c -> System.out.printf("chunk#%d [%s > %s] 长度=%d%n",
                c.getIndex(), c.getCategory(), c.getRuleName(), c.getContent().length()));
    }

    @Test
    void chunk_bestPractice_shouldReturnCorrectChunks() {
        RuleChunker chunker = new RuleChunker();
        Path path = Paths.get("src/main/resources/knowledge/best-practice.md");

        List<RuleChunk> chunks = chunker.chunk(path, "best-practice.md");

        // best-practice.md 有 23 个二级标题
        assertEquals(23, chunks.size());

        RuleChunk chunk = chunks.stream()
                .filter(c -> "空指针防御".equals(c.getCategory()))
                .findFirst()
                .orElseThrow();
        assertEquals("使用 Optional 代替 null", chunk.getRuleName());
    }
}