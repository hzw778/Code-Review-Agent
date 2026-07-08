package com.codereview.agent.agent.tool;

import com.codereview.agent.config.AgentProperties;
import com.codereview.agent.tool.AgentTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件读取工具：读取指定文件的内容（可指定行范围）。
 *
 * <p>使用场景：
 * <ul>
 *   <li>AST 只能分析 diff 涉及的 Java 文件，Agent 需要看上下文（如调用方、被调用方）时用此工具</li>
 *   <li>查看非 Java 文件（如 pom.xml、application.yml、配置文件）的内容</li>
 *   <li>查看某文件特定行范围的代码（聚焦关键片段，避免 token 爆炸）</li>
 * </ul>
 *
 * <p><b>安全设计（关键）</b>：只允许读取 workdir 目录内的文件，防止路径穿越攻击。
 * Agent 的 filePath 参数来自 LLM，LLM 可能被 prompt 注入诱导读取
 * /etc/passwd、~/.ssh/id_rsa 等敏感文件。通过 canonical path 比较强制
 * 把读取范围限制在 workdir 内，是 Agent 工具安全的必备措施。
 *
 * <p>token 防护：限制最大读取行数（默认 500 行），超过则截断并提示 Agent
 * 用 startLine/endLine 分段读取。避免一次读入超大文件撑爆 LLM 上下文窗口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileReadTool implements AgentTool {

    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 单次最大读取行数（防止 token 爆炸） */
    private static final int MAX_LINES = 500;

    @Override
    public String getName() {
        return "FileReadTool";
    }

    @Override
    public String getDescription() {
        return "读取指定文件的内容（可指定行范围）。当需要查看文件上下文、配置文件、或非 Java 文件内容时使用。"
                + "注意：filePath 必须是 workdir 内的绝对路径（可从 GitDiffTool 返回的 repoLocalPath 拼接）。";
    }

    @Override
    public String getParametersDescription() {
        return "{\"filePath\": \"文件绝对路径（必填，必须在 workdir 内）\", "
                + "\"startLine\": \"起始行号（选填，从 1 开始）\", "
                + "\"endLine\": \"结束行号（选填）\"}";
    }

    @Override
    public String execute(Map<String, Object> args) {
        String filePath = args.get("filePath") == null ? null : String.valueOf(args.get("filePath"));
        Integer startLine = toInt(args.get("startLine"), 1);
        Integer endLine = toInt(args.get("endLine"), null);

        log.info("[FileReadTool] 读取文件, filePath={}, startLine={}, endLine={}", filePath, startLine, endLine);

        if (filePath == null || filePath.isBlank()) {
            return "{\"error\": \"filePath 不能为空\"}";
        }

        try {
            // 1. 安全检查：限制在 workdir 内（防止路径穿越）
            Path workdir = Path.of(agentProperties.getWorkdir()).toAbsolutePath().normalize();
            Path target = new File(filePath).toPath().toAbsolutePath().normalize();

            if (!target.startsWith(workdir)) {
                log.warn("[FileReadTool] 拒绝读取 workdir 外的文件: {} (workdir={})", target, workdir);
                return "{\"error\": \"安全限制：只允许读取 workdir 内的文件: " + workdir + "\"}";
            }

            File file = target.toFile();
            if (!file.exists() || !file.isFile()) {
                return "{\"error\": \"文件不存在: " + filePath + "\"}";
            }

            // 2. 读取全部行
            List<String> allLines = Files.readAllLines(target);
            int totalLines = allLines.size();

            // 3. 计算实际读取范围
            int s = Math.max(1, startLine);
            int e = (endLine == null || endLine > totalLines) ? totalLines : endLine;
            if (s > totalLines) {
                return "{\"error\": \"startLine 超出文件总行数 " + totalLines + "\"}";
            }

            // 4. 超过最大行数则截断
            boolean truncated = false;
            int originalEnd = e;
            if (e - s + 1 > MAX_LINES) {
                e = s + MAX_LINES - 1;
                truncated = true;
            }

            // 5. 拼接内容（带行号，便于 Agent 引用具体行）
            StringBuilder content = new StringBuilder();
            for (int i = s; i <= e; i++) {
                content.append(i).append(": ").append(allLines.get(i - 1)).append('\n');
            }

            // 6. 序列化返回
            Map<String, Object> result = new HashMap<>();
            result.put("filePath", target.toString());
            result.put("totalLines", totalLines);
            result.put("startLine", s);
            result.put("endLine", e);
            result.put("readLines", e - s + 1);
            result.put("truncated", truncated);
            if (truncated) {
                result.put("truncatedHint", "内容超过 " + MAX_LINES + " 行已截断，原请求范围到 " + originalEnd
                        + " 行。可用 startLine=" + (e + 1) + " 继续读取下一段。");
            }
            result.put("content", content.toString());
            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("[FileReadTool] 读取文件失败: {}", filePath, e);
            return "{\"error\": \"读取文件失败: " + e.getMessage() + "\"}";
        }
    }

    /** 安全转 Integer，失败用默认值 */
    private Integer toInt(Object o, Integer def) {
        if (o == null) return def;
        try {
            return Integer.valueOf(String.valueOf(o).trim());
        } catch (Exception e) {
            return def;
        }
    }
}
