package com.codereview.agent.rag;

import com.codereview.agent.rag.model.RuleChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 规范文档分块器：按 Markdown 二级标题（##）切分规范文档。
 *
 * <p>分块策略说明：
 * <ul>
 *   <li>一级标题（#）作为 category 分组，不触发切分</li>
 *   <li>二级标题（##）作为切分点，每个 ## 下的内容构成一个完整 chunk</li>
 *   <li>chunk 内保留空行和代码块格式，保证语义完整</li>
 * </ul>
 *
 * <p>这样设计的目的是让 Agent 检索时能召回"一条完整规则"，
 * 而不是被截断的半条规则，提升 RAG 回答质量。
 */
@Slf4j
@Component
public class RuleChunker {

    /** 二级标题前缀：匹配 "## " 开头的行 */
    private static final String LEVEL2_PREFIX = "## ";

    /** 一级标题前缀：匹配 "# " 开头的行（注意不能误匹配 ## ） */
    private static final String LEVEL1_PREFIX = "# ";

    /**
     * 对指定 Markdown 文件按二级标题分块。
     *
     * @param markdownPath Markdown 文件路径
     * @param sourceName   来源标识（文件名，用于 metadata）
     * @return chunk 列表
     */
    public List<RuleChunk> chunk(Path markdownPath, String sourceName) {
        log.info("[分块] 开始分块, 文件={}, source={}", markdownPath, sourceName);
        long start = System.currentTimeMillis();

        List<String> lines;
        try {
            lines = Files.readAllLines(markdownPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("[异常] 读取规范文档失败, 文件={}", markdownPath, e);
            throw new RuntimeException("读取规范文档失败: " + markdownPath, e);
        }

        List<RuleChunk> chunks = doChunk(lines, sourceName);

        log.info("[分块] 分块完成, 文件={}, chunk数={}, 耗时={}ms",
                sourceName, chunks.size(), System.currentTimeMillis() - start);
        return chunks;
    }

        /**
         * 核心分块逻辑：逐行扫描，遇到二级标题切分。
         *
         * @param lines     文件所有行
         * @param sourceName 来源文件名
         * @return chunk 列表
     */
    private List<RuleChunk> doChunk(List<String> lines, String sourceName) {
        List<RuleChunk> chunks = new ArrayList<>();

        String currentCategory = "";   // 当前一级标题
        String currentRuleName = "";   // 当前二级标题
        StringBuilder buffer = new StringBuilder();  // 当前 chunk 内容缓冲区
        int index = 0;

        for (String line : lines) {
            if (isLevel1Heading(line)) {
                // 一级标题：只更新 category，不放入 buffer（避免生成无意义的空 chunk）
                currentCategory = line.substring(LEVEL1_PREFIX.length()).trim();
                continue;
            }

            if (isLevel2Heading(line)) {
                // 二级标题：切分前一个 chunk（trim 后非空才封装，过滤掉只有空行的伪 chunk）
                if (buffer.toString().trim().length() > 0) {
                    chunks.add(buildChunk(buffer, sourceName, currentCategory, currentRuleName, index));
                    index++;
                    buffer = new StringBuilder();
                }
                currentRuleName = line.substring(LEVEL2_PREFIX.length()).trim();
                buffer.append(line).append('\n');
                continue;
            }

            // 普通行：追加到缓冲区
            buffer.append(line).append('\n');
        }

        // 处理最后一块（trim 后非空才封装）
        if (buffer.toString().trim().length() > 0) {
            chunks.add(buildChunk(buffer, sourceName, currentCategory, currentRuleName, index));
        }

        return chunks;
    }

    /**
     * 封装一个 chunk。
     */
    private RuleChunk buildChunk(StringBuilder buffer, String source,
                                  String category, String ruleName, int index) {
        String content = buffer.toString().trim();
        log.debug("[分块] 生成 chunk#{}, category={}, ruleName={}, 内容长度={}",
                index, category, ruleName, content.length());
        return RuleChunk.builder()
                .content(content)
                .source(source)
                .category(category)
                .ruleName(ruleName)
                .index(index)
                .build();
    }

    /**
     * 判断是否一级标题（# 开头，但非 ##）。
     * 注意：必须先判断 ##，否则 ## 会被误判为 #。
     */
    private boolean isLevel1Heading(String line) {
        return line.startsWith(LEVEL1_PREFIX) && !line.startsWith(LEVEL2_PREFIX);
    }

    /**
     * 判断是否二级标题。
     */
    private boolean isLevel2Heading(String line) {
        return line.startsWith(LEVEL2_PREFIX);
    }
}