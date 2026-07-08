package com.codereview.agent.agent.tool;

import com.codereview.agent.config.AgentProperties;
import com.codereview.agent.git.GitOperationService;
import com.codereview.agent.git.model.DiffEntry;
import com.codereview.agent.git.model.DiffFile;
import com.codereview.agent.repository.entity.GitRepo;
import com.codereview.agent.tool.AgentTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Git Diff 工具：获取 commit 的代码变更。
 *
 * <p>包装阶段2的 GitOperationService，供 Agent ReAct 循环调用。
 *
 * <p>桥接逻辑：GitOperationService.getDiff 需要 GitRepo 实体（含本地克隆路径），
 * 本工具负责把 Agent 传来的 repoUrl + commitId 桥接为 GitRepo：
 * 1. 在 workdir 下按仓库名创建目录
 * 2. 构造 GitRepo 实体
 * 3. 调 cloneRepo 克隆
 * 4. 调 getDiff 获取变更
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitDiffTool implements AgentTool {

    private final GitOperationService gitOperationService;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "GitDiffTool";
    }

    @Override
    public String getDescription() {
        return "获取指定 commit 的代码变更（diff）。当需要查看用户提交了哪些代码改动时使用。";
    }

    @Override
    public String getParametersDescription() {
        return "{\"repoUrl\": \"Git 仓库地址（必填，如 https://github.com/user/repo.git）\", "
                + "\"commitId\": \"commit 的 hash 值（必填）\"}";
    }

    @Override
    public String execute(Map<String, Object> args) {
        String repoUrl = (String) args.get("repoUrl");
        String commitId = (String) args.get("commitId");

        log.info("[GitDiffTool] 获取 diff, repoUrl={}, commitId={}", repoUrl, commitId);

        if (repoUrl == null || repoUrl.isBlank()) {
            return "{\"error\": \"repoUrl 不能为空\"}";
        }
        if (commitId == null || commitId.isBlank()) {
            return "{\"error\": \"commitId 不能为空\"}";
        }

        try {
            // 1. 从 repoUrl 提取仓库名
            String repoName = extractRepoName(repoUrl);

            // 2. 构造本地克隆路径
            String localPath = Path.of(agentProperties.getWorkdir(), repoName).toString();

            // 3. 尝试克隆：main → master → 不指定分支（让 JGit 自动检测默认分支）
            DiffEntry diff = null;
            String[] branchCandidates = {"main", "master", null};  // null = 不指定分支
            Exception lastError = null;
            for (String branch : branchCandidates) {
                try {
                    // 克隆前递归清理目标目录（避免上次失败残留非空目录）
                    File dir = new File(localPath);
                    if (dir.exists()) {
                        deleteDirectory(dir);
                    }

                    GitRepo repo = new GitRepo();
                    repo.setName(repoName);
                    repo.setUrl(repoUrl);
                    repo.setType(GitRepo.RepoType.REMOTE);
                    repo.setDefaultBranch(branch);  // null 时 GitOperationService 不传 --branch
                    repo.setLocalPath(localPath);

                    gitOperationService.cloneRepo(repo);
                    diff = gitOperationService.getDiff(repo, commitId);
                    log.info("[GitDiffTool] 克隆成功, branch={}", branch == null ? "auto" : branch);
                    break;
                } catch (Exception e) {
                    lastError = e;
                    log.warn("[GitDiffTool] branch={} 克隆失败: {}", branch == null ? "auto" : branch, e.getMessage());
                }
            }
            if (diff == null) {
                throw new RuntimeException("克隆失败（尝试 main/master/auto 均失败）: " + lastError.getMessage(), lastError);
            }

            // 4. 序列化返回
            return serializeDiff(diff, repoName);
        } catch (Exception e) {
            log.error("[GitDiffTool] 获取 diff 失败", e);
            return "{\"error\": \"获取 diff 失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 递归删除目录（解决 Files.deleteIfExists 不能删非空目录的问题）。
     */
    private void deleteDirectory(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteDirectory(child);
                } else {
                    child.delete();
                }
            }
        }
        dir.delete();
    }

    /**
     * 序列化 diff 结果（精简，只返回文件名和行数统计）。
     */
    private String serializeDiff(DiffEntry diff, String repoName) throws Exception {
        List<Map<String, Object>> files = diff.getFiles().stream()
                .map(f -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("newPath", f.getNewPath());
                    m.put("changeType", f.getChangeType());
                    m.put("addedLines", f.getAddedLines().size());
                    m.put("removedLines", f.getRemovedLines().size());
                    return m;
                })
                .toList();

        Map<String, Object> result = new HashMap<>();
        result.put("repoName", repoName);
        result.put("repoLocalPath", Path.of(agentProperties.getWorkdir(), repoName).toString());
        result.put("commitId", diff.getCommitId());
        result.put("author", diff.getAuthor());
        result.put("commitMessage", diff.getCommitMessage());
        result.put("totalFiles", diff.getFiles().size());
        result.put("totalAdditions", diff.getAddedLineCount());
        result.put("totalDeletions", diff.getRemovedLineCount());
        result.put("files", files);
        return objectMapper.writeValueAsString(result);
    }

    /**
     * 从 repoUrl 提取仓库名。
     * 如 https://github.com/user/demo.git → demo
     */
    private String extractRepoName(String repoUrl) {
        String name = repoUrl.substring(repoUrl.lastIndexOf('/') + 1);
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }
}
